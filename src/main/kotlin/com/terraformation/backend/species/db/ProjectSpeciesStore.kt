package com.terraformation.backend.species.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.species.model.ProjectSpeciesOverride
import com.terraformation.backend.species.model.SourcedSpeciesNativity
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class ProjectSpeciesStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val speciesNativityCalculator: SpeciesNativityCalculator,
) {
  fun assignProjects(assignments: Map<SpeciesId, Set<ProjectId>>) {
    require(assignments.isNotEmpty()) { "No species assignments specified" }

    val organizationId = checkOrganization(assignments)

    val speciesIdsByProject: Map<ProjectId, List<SpeciesId>> =
        assignments.entries
            .flatMap { (speciesId, projectIds) ->
              projectIds.map { it to speciesId }
            }
            .groupBy { (projectId, _) -> projectId }
            .mapValues { (_, projectAndSpecies) -> projectAndSpecies.map { it.second } }

    val calculatedNativities: Map<Pair<SpeciesId, ProjectId>, SourcedSpeciesNativity> =
        calculateNativities(speciesIdsByProject)

    val rows = assignments.flatMap { (speciesId, projectIds) ->
      projectIds.map { projectId ->
        val calculatedNativity = calculatedNativities[speciesId to projectId]

        DSL.row(
            calculatedNativity?.datasetDate,
            calculatedNativity?.datasetType,
            calculatedNativity?.speciesNativity,
            organizationId,
            projectId,
            speciesId,
        )
      }
    }

    with(PROJECT_SPECIES) {
      dslContext
          .insertInto(
              PROJECT_SPECIES,
              CALCULATED_NATIVITY_DATASET_DATE,
              CALCULATED_NATIVITY_DATASET_TYPE_ID,
              CALCULATED_NATIVITY_ID,
              ORGANIZATION_ID,
              PROJECT_ID,
              SPECIES_ID,
          )
          .valuesOfRows(rows)
          .onConflictDoNothing()
          .execute()
    }
  }

  fun recalculateNativities(projectId: ProjectId) {
    val speciesIds =
        dslContext
            .select(PROJECT_SPECIES.SPECIES_ID)
            .from(PROJECT_SPECIES)
            .where(PROJECT_SPECIES.PROJECT_ID.eq(projectId))
            .fetch(PROJECT_SPECIES.SPECIES_ID.asNonNullable())
    if (speciesIds.isEmpty()) {
      return
    }

    val nativities = calculateNativities(mapOf(projectId to speciesIds))

    with(PROJECT_SPECIES) {
      val rows = speciesIds.map { speciesId ->
        val sourcedNativity = nativities[speciesId to projectId]

        DSL.row(
            DSL.value(speciesId, SPECIES_ID.dataType),
            DSL.value(sourcedNativity?.datasetDate, CALCULATED_NATIVITY_DATASET_DATE.dataType),
            DSL.value(sourcedNativity?.datasetType, CALCULATED_NATIVITY_DATASET_TYPE_ID.dataType),
            DSL.value(sourcedNativity?.speciesNativity, CALCULATED_NATIVITY_ID.dataType),
        )
      }

      val updateValues = DSL.values(*(rows.toTypedArray()))

      val speciesIdField = updateValues.field(0, SPECIES_ID.dataType)!!
      val datasetDateField = updateValues.field(1, CALCULATED_NATIVITY_DATASET_DATE.dataType)!!
      val datasetTypeField = updateValues.field(2, CALCULATED_NATIVITY_DATASET_TYPE_ID.dataType)!!
      val nativityField = updateValues.field(3, CALCULATED_NATIVITY_ID.dataType)!!

      dslContext
          .update(PROJECT_SPECIES)
          .set(CALCULATED_NATIVITY_DATASET_DATE, datasetDateField)
          .set(CALCULATED_NATIVITY_DATASET_TYPE_ID, datasetTypeField)
          .set(CALCULATED_NATIVITY_ID, nativityField)
          .set(OVERRIDDEN_BY, DSL.castNull(OVERRIDDEN_BY.dataType))
          .set(OVERRIDDEN_NATIVITY_ID, DSL.castNull(OVERRIDDEN_NATIVITY_ID.dataType))
          .set(OVERRIDDEN_JUSTIFICATION, DSL.castNull(OVERRIDDEN_JUSTIFICATION.dataType))
          .set(OVERRIDDEN_TIME, DSL.castNull(OVERRIDDEN_TIME.dataType))
          .from(updateValues)
          .where(PROJECT_ID.eq(projectId))
          .and(SPECIES_ID.eq(speciesIdField))
          .execute()
    }
  }

  fun overridePerProjectData(overrides: List<ProjectSpeciesOverride>) {
    require(overrides.isNotEmpty()) { "No overrides specified" }

    require(overrides.distinctBy { it.speciesId to it.projectId }.size == overrides.size) {
      "Duplicate species/project in overrides list"
    }

    val organizationId =
        checkOrganization(
            overrides
                .groupingBy { it.speciesId }
                .aggregate { _, accumulator, element, _ ->
                  accumulator?.let { it + setOfNotNull(element.projectId) }
                      ?: setOfNotNull(element.projectId)
                }
        )
    val now = clock.instant()
    val userId = currentUser().userId

    val rows = overrides.map { override ->
      DSL.row(
          organizationId,
          userId,
          override.overriddenJustification,
          override.overriddenNativity,
          now,
          override.projectId,
          override.speciesId,
      )
    }

    with(PROJECT_SPECIES) {
      dslContext
          .insertInto(
              PROJECT_SPECIES,
              ORGANIZATION_ID,
              OVERRIDDEN_BY,
              OVERRIDDEN_JUSTIFICATION,
              OVERRIDDEN_NATIVITY_ID,
              OVERRIDDEN_TIME,
              PROJECT_ID,
              SPECIES_ID,
          )
          .valuesOfRows(rows)
          .onConflict(ORGANIZATION_ID, PROJECT_ID, SPECIES_ID)
          .doUpdate()
          .set(OVERRIDDEN_BY, DSL.excluded(OVERRIDDEN_BY))
          .set(OVERRIDDEN_JUSTIFICATION, DSL.excluded(OVERRIDDEN_JUSTIFICATION))
          .set(OVERRIDDEN_NATIVITY_ID, DSL.excluded(OVERRIDDEN_NATIVITY_ID))
          .set(OVERRIDDEN_TIME, DSL.excluded(OVERRIDDEN_TIME))
          .execute()
    }
  }

  fun removeProjects(assignments: Map<SpeciesId, Set<ProjectId>>) {
    require(assignments.isNotEmpty()) { "No species assignments specified" }

    val organizationId = checkOrganization(assignments)

    val rows = assignments.flatMap { (speciesId, projectIds) ->
      projectIds.map { projectId -> DSL.row(organizationId, projectId, speciesId) }
    }

    dslContext
        .deleteFrom(PROJECT_SPECIES)
        .where(
            DSL.row(
                    PROJECT_SPECIES.ORGANIZATION_ID,
                    PROJECT_SPECIES.PROJECT_ID,
                    PROJECT_SPECIES.SPECIES_ID,
                )
                .`in`(rows)
        )
        .execute()
  }

  /**
   * Verifies the current user may update the species and read the projects, and that every species
   * and project belongs to the same organization.
   */
  private fun checkOrganization(assignments: Map<SpeciesId, Set<ProjectId>>): OrganizationId {
    val speciesIds = assignments.keys
    val projectIds = assignments.values.flatten().toSet()

    requirePermissions {
      speciesIds.forEach { updateSpecies(it) }
      projectIds.forEach { readProject(it) }
    }

    val organizationIds =
        dslContext
            .select(SPECIES.ORGANIZATION_ID)
            .from(SPECIES)
            .where(SPECIES.ID.`in`(speciesIds))
            .union(
                DSL.select(PROJECTS.ORGANIZATION_ID)
                    .from(PROJECTS)
                    .where(PROJECTS.ID.`in`(projectIds))
            )
            .fetch { it.value1() }

    if (organizationIds.toSet().size > 1) {
      throw ProjectInDifferentOrganizationException()
    }

    return organizationIds.first()
  }

  private fun calculateNativities(
      speciesIdsByProject: Map<ProjectId, Collection<SpeciesId>>
  ): Map<Pair<SpeciesId, ProjectId>, SourcedSpeciesNativity> {
    val locationsByProjectId = getProjectLocations(speciesIdsByProject.keys)

    val speciesIdsForProjectsWithLocations = speciesIdsByProject.filterKeys { projectId ->
      locationsByProjectId[projectId]?.botanicalCountryCode != null &&
          locationsByProjectId[projectId]?.countryCode != null
    }
    val namesBySpeciesId =
        getSpeciesScientificNames(speciesIdsForProjectsWithLocations.values.flatten().toSet())

    // Group projects by location so that a given species is only looked up once for a given
    // location, even if it's assigned to multiple projects that share that location.
    val projectIdsByLocation =
        speciesIdsForProjectsWithLocations.keys.groupBy { locationsByProjectId.getValue(it) }

    val nativitiesByLocation: Map<ProjectLocation, Map<String, SourcedSpeciesNativity>> =
        projectIdsByLocation.mapValues { (location, projectIds) ->
          val scientificNames =
              projectIds
                  .flatMap { speciesIdsForProjectsWithLocations.getValue(it) }
                  .mapNotNull { namesBySpeciesId[it] }
                  .toSet()
          speciesNativityCalculator.calculateNativities(
              location.botanicalCountryCode!!,
              location.countryCode!!,
              scientificNames,
          )
        }

    return speciesIdsForProjectsWithLocations
        .flatMap { (projectId, speciesIds) ->
          val location = locationsByProjectId.getValue(projectId)
          val nativitiesByName = nativitiesByLocation.getValue(location)
          speciesIds.mapNotNull { speciesId ->
            val scientificName = namesBySpeciesId[speciesId] ?: return@mapNotNull null
            val sourcedNativity = nativitiesByName[scientificName] ?: return@mapNotNull null
            (speciesId to projectId) to sourcedNativity
          }
        }
        .toMap()
  }

  private fun getProjectLocations(projectIds: Set<ProjectId>): Map<ProjectId, ProjectLocation> {
    return dslContext
        .select(
            PROJECTS.ID,
            DSL.case_()
                .`when`(
                    PROJECTS.BOTANICAL_COUNTRY_CODE.isNotNull.and(PROJECTS.COUNTRY_CODE.isNotNull),
                    PROJECTS.BOTANICAL_COUNTRY_CODE,
                )
                .else_(ORGANIZATIONS.BOTANICAL_COUNTRY_CODE),
            DSL.case_()
                .`when`(
                    PROJECTS.BOTANICAL_COUNTRY_CODE.isNotNull.and(PROJECTS.COUNTRY_CODE.isNotNull),
                    PROJECTS.COUNTRY_CODE,
                )
                .else_(ORGANIZATIONS.COUNTRY_CODE),
        )
        .from(PROJECTS)
        // Only fall back to org-level location if org has only one project and has both locations.
        .leftJoin(ORGANIZATIONS)
        .on(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
        .and(ORGANIZATIONS.BOTANICAL_COUNTRY_CODE.isNotNull)
        .and(ORGANIZATIONS.COUNTRY_CODE.isNotNull)
        .and(
            DSL.field(
                    DSL.selectCount()
                        .from(PROJECTS)
                        .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                )
                .eq(1)
        )
        .where(PROJECTS.ID.`in`(projectIds))
        .fetchMap(PROJECTS.ID.asNonNullable()) { ProjectLocation(it.value2(), it.value3()) }
  }

  private fun getSpeciesScientificNames(speciesIds: Collection<SpeciesId>): Map<SpeciesId, String> {
    return dslContext
        .select(SPECIES.ID, SPECIES.SCIENTIFIC_NAME)
        .from(SPECIES)
        .where(SPECIES.ID.`in`(speciesIds))
        .fetchMap(SPECIES.ID.asNonNullable(), SPECIES.SCIENTIFIC_NAME.asNonNullable())
  }

  data class ProjectLocation(
      val botanicalCountryCode: String?,
      val countryCode: String?,
  )
}
