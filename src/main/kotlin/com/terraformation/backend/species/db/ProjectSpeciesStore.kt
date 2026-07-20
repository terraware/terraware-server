package com.terraformation.backend.species.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ExternalDatasetType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesNativity
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.species.model.ProjectSpeciesOverride
import com.terraformation.backend.species.model.SourcedSpeciesNativity
import jakarta.inject.Named
import java.time.InstantSource
import java.time.LocalDate
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Row4
import org.jooq.impl.DSL

@Named
class ProjectSpeciesStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val speciesNativityCalculator: SpeciesNativityCalculator,
) {
  private val log = perClassLogger()

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

    dslContext.transaction { _ ->
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

        val speciesIdsWithAssignments = assignments.filterValues { it.isNotEmpty() }.keys

        dslContext
            .deleteFrom(PROJECT_SPECIES)
            .where(ORGANIZATION_ID.eq(organizationId))
            .and(PROJECT_ID.isNull)
            .and(SPECIES_ID.`in`(speciesIdsWithAssignments))
            .execute()
      }
    }
  }

  /**
   * Recalculates the organization-level nativity for a species if the organization has fewer than
   * two projects and the species is not assigned to a project.
   */
  fun recalculateNativity(organizationId: OrganizationId, speciesId: SpeciesId) {
    requirePermissions { updateSpecies(speciesId) }

    val numProjects = dslContext.fetchCount(PROJECTS, PROJECTS.ORGANIZATION_ID.eq(organizationId))
    if (numProjects > 1) {
      return
    }

    val numProjectAssignments =
        dslContext.fetchCount(
            PROJECT_SPECIES,
            PROJECT_SPECIES.SPECIES_ID.eq(speciesId),
            PROJECT_SPECIES.PROJECT_ID.isNotNull,
        )
    if (numProjectAssignments > 0) {
      return
    }

    val sourcedNativity =
        calculateOrganizationNativities(organizationId, setOf(speciesId))[speciesId]

    if (sourcedNativity != null) {
      with(PROJECT_SPECIES) {
        dslContext
            .insertInto(PROJECT_SPECIES)
            .set(CALCULATED_NATIVITY_DATASET_DATE, sourcedNativity.datasetDate)
            .set(CALCULATED_NATIVITY_DATASET_TYPE_ID, sourcedNativity.datasetType)
            .set(CALCULATED_NATIVITY_ID, sourcedNativity.speciesNativity)
            .set(ORGANIZATION_ID, organizationId)
            .set(SPECIES_ID, speciesId)
            .onConflict(ORGANIZATION_ID, PROJECT_ID, SPECIES_ID)
            .doUpdate()
            .set(CALCULATED_NATIVITY_DATASET_DATE, sourcedNativity.datasetDate)
            .set(CALCULATED_NATIVITY_DATASET_TYPE_ID, sourcedNativity.datasetType)
            .set(CALCULATED_NATIVITY_ID, sourcedNativity.speciesNativity)
            .execute()
      }
    }
  }

  /**
   * Recalculates nativities for all the species in an organization if the organization has fewer
   * than two projects.
   */
  fun recalculateNativities(organizationId: OrganizationId) {
    val numProjects = dslContext.fetchCount(PROJECTS, PROJECTS.ORGANIZATION_ID.eq(organizationId))
    if (numProjects > 1) {
      log.info(
          "Organization $organizationId has $numProjects projects; not recalculating nativities"
      )
      return
    }

    val speciesIds =
        with(SPECIES) {
          dslContext
              .select(ID)
              .from(SPECIES)
              .where(ORGANIZATION_ID.eq(organizationId))
              .and(DELETED_TIME.isNull)
              .fetch(ID.asNonNullable())
        }
    if (speciesIds.isEmpty()) {
      return
    }

    val nativities = calculateOrganizationNativities(organizationId, speciesIds)

    val existingSpeciesIds =
        dslContext
            .select(PROJECT_SPECIES.SPECIES_ID)
            .from(PROJECT_SPECIES)
            .where(PROJECT_SPECIES.ORGANIZATION_ID.eq(organizationId))
            .and(PROJECT_SPECIES.SPECIES_ID.`in`(speciesIds))
            .fetchSet(PROJECT_SPECIES.SPECIES_ID.asNonNullable())

    // Species that already have a project_species row (whether or not it's associated with a
    // project) get their existing row's calculated nativity updated in place. This avoids inserting
    // a redundant row with a null project ID for a species that's already tied to the org's single
    // project.
    val existingRows = existingSpeciesIds.map { speciesId ->
      rowForUpdate(speciesId, nativities[speciesId])
    }
    if (existingRows.isNotEmpty()) {
      updateCalculatedNativities(existingRows, PROJECT_SPECIES.ORGANIZATION_ID.eq(organizationId))
    }

    // Species that don't have a project_species row yet get a new row with no project ID.
    val missingSpeciesIds = speciesIds.filterNot { it in existingSpeciesIds }
    if (missingSpeciesIds.isNotEmpty()) {
      insertCalculatedNativities(organizationId, missingSpeciesIds, nativities)
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

    val rows = speciesIds.map { speciesId ->
      rowForUpdate(speciesId, nativities[speciesId to projectId])
    }

    updateCalculatedNativities(rows, PROJECT_SPECIES.PROJECT_ID.eq(projectId))
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
   * Deletes the project associations and project- and org-level nativities for a species. This is
   * called when a species is soft-deleted; we don't want it to continue to be associated with
   * projects.
   */
  fun deleteForSpecies(speciesId: SpeciesId) {
    requirePermissions { updateSpecies(speciesId) }

    dslContext.deleteFrom(PROJECT_SPECIES).where(PROJECT_SPECIES.SPECIES_ID.eq(speciesId)).execute()
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

  private fun calculateOrganizationNativities(
      organizationId: OrganizationId,
      speciesIds: Collection<SpeciesId>,
  ): Map<SpeciesId, SourcedSpeciesNativity> {
    val location = getOrganizationLocation(organizationId)
    if (location.botanicalCountryCode == null || location.countryCode == null) {
      return emptyMap()
    }

    val namesBySpeciesId = getSpeciesScientificNames(speciesIds)

    val nativities =
        speciesNativityCalculator.calculateNativities(
            location.botanicalCountryCode,
            location.countryCode,
            namesBySpeciesId.values,
        )

    return speciesIds.associateWith { speciesId ->
      nativities.getValue(namesBySpeciesId.getValue(speciesId))
    }
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

  private fun rowForUpdate(
      speciesId: SpeciesId,
      sourcedNativity: SourcedSpeciesNativity?,
  ): Row4<SpeciesId?, LocalDate?, ExternalDatasetType?, SpeciesNativity?> {
    return with(PROJECT_SPECIES) {
      DSL.row(
          DSL.value(speciesId, SPECIES_ID.dataType),
          DSL.value(sourcedNativity?.datasetDate, CALCULATED_NATIVITY_DATASET_DATE.dataType),
          DSL.value(sourcedNativity?.datasetType, CALCULATED_NATIVITY_DATASET_TYPE_ID.dataType),
          DSL.value(sourcedNativity?.speciesNativity, CALCULATED_NATIVITY_ID.dataType),
      )
    }
  }

  private fun updateCalculatedNativities(
      rows: List<Row4<SpeciesId?, LocalDate?, ExternalDatasetType?, SpeciesNativity?>>,
      scopeCondition: Condition,
  ): Int {
    with(PROJECT_SPECIES) {
      val updateValues = DSL.values(*(rows.toTypedArray()))

      val speciesIdField = updateValues.field(0, SPECIES_ID.dataType)!!
      val datasetDateField = updateValues.field(1, CALCULATED_NATIVITY_DATASET_DATE.dataType)!!
      val datasetTypeField = updateValues.field(2, CALCULATED_NATIVITY_DATASET_TYPE_ID.dataType)!!
      val nativityField = updateValues.field(3, CALCULATED_NATIVITY_ID.dataType)!!

      return dslContext
          .update(PROJECT_SPECIES)
          .set(CALCULATED_NATIVITY_DATASET_DATE, datasetDateField)
          .set(CALCULATED_NATIVITY_DATASET_TYPE_ID, datasetTypeField)
          .set(CALCULATED_NATIVITY_ID, nativityField)
          .setNull(OVERRIDDEN_BY)
          .setNull(OVERRIDDEN_NATIVITY_ID)
          .setNull(OVERRIDDEN_JUSTIFICATION)
          .setNull(OVERRIDDEN_TIME)
          .from(updateValues)
          .where(scopeCondition)
          .and(SPECIES_ID.eq(speciesIdField))
          .execute()
    }
  }

  private fun insertCalculatedNativities(
      organizationId: OrganizationId,
      speciesIds: Collection<SpeciesId>,
      nativities: Map<SpeciesId, SourcedSpeciesNativity>,
  ) {
    val rows = speciesIds.map { speciesId ->
      val sourcedNativity = nativities[speciesId]

      DSL.row(
          sourcedNativity?.datasetDate,
          sourcedNativity?.datasetType,
          sourcedNativity?.speciesNativity,
          organizationId,
          speciesId,
      )
    }

    with(PROJECT_SPECIES) {
      dslContext
          .insertInto(
              PROJECT_SPECIES,
              CALCULATED_NATIVITY_DATASET_DATE,
              CALCULATED_NATIVITY_DATASET_TYPE_ID,
              CALCULATED_NATIVITY_ID,
              ORGANIZATION_ID,
              SPECIES_ID,
          )
          .valuesOfRows(rows)
          .onConflictDoNothing()
          .execute()
    }
  }

  private fun getOrganizationLocation(organizationId: OrganizationId): ProjectLocation {
    return with(ORGANIZATIONS) {
      dslContext
          .select(BOTANICAL_COUNTRY_CODE, COUNTRY_CODE)
          .from(ORGANIZATIONS)
          .where(ID.eq(organizationId))
          .fetchSingle { ProjectLocation(it.value1(), it.value2()) }
    }
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
