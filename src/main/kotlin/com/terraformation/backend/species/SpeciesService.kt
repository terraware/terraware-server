package com.terraformation.backend.species

import com.terraformation.backend.customer.event.OrganizationLocationUpdatedEvent
import com.terraformation.backend.customer.event.ProjectUpdatedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ScientificNameExistsException
import com.terraformation.backend.db.SpeciesInUseException
import com.terraformation.backend.db.SpeciesProblemHasNoSuggestionException
import com.terraformation.backend.db.SpeciesProblemNotFoundException
import com.terraformation.backend.db.default_schema.ExternalDatasetType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesProblemField
import com.terraformation.backend.db.default_schema.SpeciesProblemId
import com.terraformation.backend.db.default_schema.SpeciesProblemType
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.species.db.ExternalDatasetStore
import com.terraformation.backend.species.db.GbifStore
import com.terraformation.backend.species.db.ProjectSpeciesStore
import com.terraformation.backend.species.db.SpeciesChecker
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.event.SpeciesEditedEvent
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.species.model.GbifTaxonModel
import com.terraformation.backend.species.model.NewSpeciesModel
import com.terraformation.backend.species.model.SpeciesDataSourceModel
import jakarta.inject.Named
import java.time.LocalDate
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.dao.DuplicateKeyException

@Named
class SpeciesService(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val externalDatasetStore: ExternalDatasetStore,
    private val gbifStore: GbifStore,
    private val projectSpeciesStore: ProjectSpeciesStore,
    private val speciesChecker: SpeciesChecker,
    private val speciesStore: SpeciesStore,
) {
  /** Creates a new species and checks it for potential problems. */
  fun createSpecies(model: NewSpeciesModel): SpeciesId {
    return dslContext.transactionResult { _ ->
      val speciesDetails: GbifTaxonModel? by lazy {
        gbifStore.fetchOneByScientificName(model.scientificName)
      }
      val gbifSource: SpeciesDataSourceModel by lazy {
        SpeciesDataSourceModel(
            externalDatasetStore.getDatasetDate(ExternalDatasetType.GBIF),
            ExternalDatasetType.GBIF,
        )
      }
      val commonNameSource =
          if (
              model.commonName != null &&
                  speciesDetails?.vernacularNames?.any { it.name == model.commonName } == true
          ) {
            gbifSource
          } else {
            null
          }
      val familyNameSource =
          if (model.familyName != null && speciesDetails?.familyName == model.familyName) {
            gbifSource
          } else {
            null
          }

      val populatedModel =
          model.copy(commonNameSource = commonNameSource, familyNameSource = familyNameSource)

      val speciesId = speciesStore.createSpecies(populatedModel)
      speciesChecker.checkSpecies(speciesId)
      speciesId
    }
  }

  /** Updates an existing species and checks it for newly-introduced problems. */
  fun updateSpecies(model: ExistingSpeciesModel): ExistingSpeciesModel {
    return dslContext.transactionResult { _ ->
      val existingRow = speciesStore.fetchSpeciesById(model.id)
      val modelWithSources = freshenNameSources(existingRow, model)

      val updatedRow = speciesStore.updateSpecies(modelWithSources)
      speciesChecker.recheckSpecies(existingRow, updatedRow)

      eventPublisher.publishEvent(SpeciesEditedEvent(species = updatedRow))

      updatedRow
    }
  }

  /** Deletes a species if it is not used in the organization. throws [SpeciesInUseException] */
  fun deleteSpecies(speciesId: SpeciesId) {
    requirePermissions { deleteSpecies(speciesId) }

    if (speciesStore.isInUse(speciesId)) {
      throw SpeciesInUseException(speciesId)
    }

    speciesStore.deleteSpecies(speciesId)
  }

  fun acceptProblemSuggestion(problemId: SpeciesProblemId): ExistingSpeciesModel {
    val problem = speciesStore.fetchProblemById(problemId)
    val speciesId = problem.speciesId ?: throw SpeciesProblemNotFoundException(problemId)
    val existingSpecies = speciesStore.fetchSpeciesById(speciesId)

    val fieldId = problem.fieldId ?: throw IllegalStateException("Species problem had no field")
    val typeId = problem.typeId ?: throw IllegalStateException("Species problem had no type")

    val correctedSpecies =
        when (typeId) {
          SpeciesProblemType.NameNotFound -> throw SpeciesProblemHasNoSuggestionException(problemId)
          SpeciesProblemType.NameIsSynonym,
          SpeciesProblemType.NameMisspelled -> {
            // Only one field defined right now but use a "when" so this will break the build if
            // we add a second field and forget to handle it here.
            when (fieldId) {
              SpeciesProblemField.ScientificName ->
                  existingSpecies.copy(scientificName = problem.suggestedValue!!)
            }
          }
        }

    return try {
      dslContext.transactionResult { _ ->
        speciesStore.deleteProblem(problemId)
        speciesStore.updateSpecies(freshenNameSources(existingSpecies, correctedSpecies))
      }
    } catch (e: DuplicateKeyException) {
      if (fieldId == SpeciesProblemField.ScientificName) {
        throw ScientificNameExistsException(problem.suggestedValue)
      } else {
        throw e
      }
    }
  }

  @EventListener
  fun on(event: OrganizationLocationUpdatedEvent) {
    // Organization location change should only trigger a project-level species nativity
    // recaulcation if the organization has exactly one project and the project lacks a location.
    val projects =
        dslContext
            .selectFrom(PROJECTS)
            .where(PROJECTS.ORGANIZATION_ID.eq(event.organizationId))
            .limit(2)
            .fetch()
    if (
        projects.size == 1 &&
            (projects[0].botanicalCountryCode == null || projects[0].countryCode == null)
    ) {
      projectSpeciesStore.recalculateNativities(projects[0].id!!)
    }
  }

  @EventListener
  fun on(event: ProjectUpdatedEvent) {
    if (
        event.changedFrom.botanicalCountryCode != event.changedTo.botanicalCountryCode ||
            event.changedFrom.countryCode != event.changedTo.countryCode
    ) {
      projectSpeciesStore.recalculateNativities(event.projectId)
    }
  }

  /**
   * Populates the sources of the common and family names as part of a species update.
   *
   * The basic approach is:
   *
   * - If the name is being set to null, clear its existing source, if any.
   * - If both the name and the scientific name haven't changed and the name already has a source,
   *   keep the existing source, even if the current version of the source no longer matches the
   *   name.
   * - If either the name in question or the scientific name has changed, recalculate its source.
   *   This can cause an existing source to be cleared if the new name doesn't match the GBIF data.
   */
  private fun freshenNameSources(
      changedFrom: ExistingSpeciesModel,
      changedTo: ExistingSpeciesModel,
  ): ExistingSpeciesModel {
    val datasetDate: LocalDate by lazy {
      externalDatasetStore.getDatasetDate(ExternalDatasetType.GBIF)
    }
    val speciesDetails: GbifTaxonModel? by lazy {
      gbifStore.fetchOneByScientificName(changedTo.scientificName)
    }

    val commonNameSource =
        when {
          changedTo.commonName == null -> null

          changedTo.commonName == changedFrom.commonName &&
              changedTo.scientificName == changedFrom.scientificName &&
              changedFrom.commonNameSource != null -> changedFrom.commonNameSource

          speciesDetails?.vernacularNames?.any { it.name == changedTo.commonName } == true ->
              SpeciesDataSourceModel(datasetDate, ExternalDatasetType.GBIF)

          else -> null
        }

    val familyNameSource =
        when {
          changedTo.familyName == null -> null

          changedTo.familyName == changedFrom.familyName &&
              changedTo.scientificName == changedFrom.scientificName &&
              changedFrom.familyNameSource != null -> changedFrom.familyNameSource

          speciesDetails?.familyName == changedTo.familyName ->
              SpeciesDataSourceModel(datasetDate, ExternalDatasetType.GBIF)

          else -> null
        }

    return changedTo.copy(commonNameSource = commonNameSource, familyNameSource = familyNameSource)
  }
}
