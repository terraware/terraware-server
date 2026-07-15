package com.terraformation.backend.species

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ScientificNameExistsException
import com.terraformation.backend.db.SpeciesInUseException
import com.terraformation.backend.db.SpeciesProblemHasNoSuggestionException
import com.terraformation.backend.db.SpeciesProblemNotFoundException
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesProblemField
import com.terraformation.backend.db.default_schema.SpeciesProblemId
import com.terraformation.backend.db.default_schema.SpeciesProblemType
import com.terraformation.backend.species.db.SpeciesChecker
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.event.SpeciesEditedEvent
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.species.model.NewSpeciesModel
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DuplicateKeyException

@Named
class SpeciesService(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val speciesChecker: SpeciesChecker,
    private val speciesStore: SpeciesStore,
) {
  /** Creates a new species and checks it for potential problems. */
  fun createSpecies(model: NewSpeciesModel): SpeciesId {
    return dslContext.transactionResult { _ ->
      val speciesId = speciesStore.createSpecies(model)
      speciesChecker.checkSpecies(speciesId)
      speciesId
    }
  }

  /** Updates an existing species and checks it for newly-introduced problems. */
  fun updateSpecies(model: ExistingSpeciesModel): ExistingSpeciesModel {
    return dslContext.transactionResult { _ ->
      val existingRow = speciesStore.fetchSpeciesById(model.id)

      val updatedRow = speciesStore.updateSpecies(model)
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
        speciesStore.updateSpecies(correctedSpecies)
      }
    } catch (e: DuplicateKeyException) {
      if (fieldId == SpeciesProblemField.ScientificName) {
        throw ScientificNameExistsException(problem.suggestedValue)
      } else {
        throw e
      }
    }
  }
}
