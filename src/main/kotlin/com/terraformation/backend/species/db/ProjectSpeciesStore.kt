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

    val pendingNativities: Map<Pair<SpeciesId, ProjectId>, SourcedSpeciesNativity> =
        calculateNativities(speciesIdsByProject)

    val rows = assignments.flatMap { (speciesId, projectIds) ->
      projectIds.map { projectId ->
        val pendingNativity = pendingNativities[speciesId to projectId]

        DSL.row(
            organizationId,
            pendingNativity?.datasetDate,
            pendingNativity?.datasetType,
            pendingNativity?.speciesNativity,
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
                ORGANIZATION_ID,
                PENDING_NATIVITY_DATASET_DATE,
                PENDING_NATIVITY_DATASET_TYPE_ID,
                PENDING_NATIVITY_ID,
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
   * Recalculates the pending nativities of a single species everywhere it appears, using the
   * location of each project it's assigned to, or the organization's location if it isn't assigned
   * to any projects. The user will have to review the new values and approve or override them.
   */
  fun resetNativities(speciesId: SpeciesId) {
    requirePermissions { updateSpecies(speciesId) }

    val projectIds =
        dslContext
            .select(PROJECT_SPECIES.PROJECT_ID)
            .from(PROJECT_SPECIES)
            .where(PROJECT_SPECIES.SPECIES_ID.eq(speciesId))
            .and(PROJECT_SPECIES.PROJECT_ID.isNotNull)
            .fetchSet(PROJECT_SPECIES.PROJECT_ID.asNonNullable())

    dslContext.transaction { _ ->
      with(PROJECT_SPECIES) {
        dslContext
            .update(PROJECT_SPECIES)
            .setNull(CALCULATED_NATIVITY_DATASET_DATE)
            .setNull(CALCULATED_NATIVITY_DATASET_TYPE_ID)
            .setNull(CALCULATED_NATIVITY_ID)
            .setNull(OVERRIDDEN_BY)
            .setNull(OVERRIDDEN_JUSTIFICATION)
            .setNull(OVERRIDDEN_NATIVITY_ID)
            .setNull(OVERRIDDEN_TIME)
            .setNull(PENDING_NATIVITY_DATASET_DATE)
            .setNull(PENDING_NATIVITY_DATASET_TYPE_ID)
            .setNull(PENDING_NATIVITY_ID)
            .where(SPECIES_ID.eq(speciesId))
            .execute()
      }

      if (projectIds.isEmpty()) {
        val organizationId =
            dslContext
                .select(SPECIES.ORGANIZATION_ID)
                .from(SPECIES)
                .where(SPECIES.ID.eq(speciesId))
                .fetchSingle { it.value1()!! }

        val orgProjectCount =
            dslContext.fetchCount(PROJECTS, PROJECTS.ORGANIZATION_ID.eq(organizationId))

        if (orgProjectCount < 2) {
          updateOrganizationNativity(organizationId, speciesId)
        }
      } else {
        val nativities = calculateNativities(projectIds.associateWith { listOf(speciesId) })

        projectIds.forEach { projectId ->
          applyNativities(
              listOf(rowForUpdate(speciesId, nativities[speciesId to projectId])),
              PROJECT_SPECIES.PROJECT_ID.eq(projectId),
              autoAccept = false,
          )
        }
      }
    }
  }

  /**
   * Recalculates the nativities for all the species in an organization if the organization has
   * fewer than two projects.
   *
   * @param autoAccept If false, the recalculated nativities become pending ones for the user to
   *   promote, and the rows' existing nativities and overrides are left alone. If true, the
   *   recalculated nativities are accepted right away and any overrides are discarded.
   */
  fun recalculateNativities(organizationId: OrganizationId, autoAccept: Boolean = false) {
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
    // project) get their existing row updated in place. This avoids inserting a redundant row with
    // a null project ID for a species that's already tied to the org's single project.
    val existingRows = existingSpeciesIds.map { speciesId ->
      rowForUpdate(speciesId, nativities[speciesId])
    }
    if (existingRows.isNotEmpty()) {
      applyNativities(
          existingRows,
          PROJECT_SPECIES.ORGANIZATION_ID.eq(organizationId),
          autoAccept,
      )
    }

    // Species that don't have a project_species row yet get a new row with no project ID.
    val missingSpeciesIds = speciesIds.filterNot { it in existingSpeciesIds }
    if (missingSpeciesIds.isNotEmpty()) {
      insertNativities(organizationId, missingSpeciesIds, nativities, autoAccept)
    }
  }

  /**
   * Recalculates the nativities of all the species assigned to a project.
   *
   * @param autoAccept If false, the recalculated nativities become pending ones for the user to
   *   promote, and the rows' existing nativities and overrides are left alone. If true, the
   *   recalculated nativities are accepted right away and any overrides are discarded.
   */
  fun recalculateNativities(projectId: ProjectId, autoAccept: Boolean = false) {
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

    applyNativities(rows, PROJECT_SPECIES.PROJECT_ID.eq(projectId), autoAccept)
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

  private fun updateOrganizationNativity(
      organizationId: OrganizationId,
      speciesId: SpeciesId,
  ) {
    val sourcedNativity =
        calculateOrganizationNativities(organizationId, setOf(speciesId))[speciesId]

    if (sourcedNativity != null) {
      dslContext.transaction { _ ->
        with(PROJECT_SPECIES) {
          dslContext
              .insertInto(PROJECT_SPECIES, ORGANIZATION_ID, SPECIES_ID)
              .values(organizationId, speciesId)
              .onConflictDoNothing()
              .execute()
        }

        applyNativities(
            listOf(rowForUpdate(speciesId, sourcedNativity)),
            PROJECT_SPECIES.ORGANIZATION_ID.eq(organizationId)
                .and(PROJECT_SPECIES.PROJECT_ID.isNull),
            autoAccept = false,
        )
      }
    }
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
          DSL.value(sourcedNativity?.datasetDate, PENDING_NATIVITY_DATASET_DATE.dataType),
          DSL.value(sourcedNativity?.datasetType, PENDING_NATIVITY_DATASET_TYPE_ID.dataType),
          DSL.value(sourcedNativity?.speciesNativity, PENDING_NATIVITY_ID.dataType),
      )
    }
  }

  /**
   * Applies recalculated nativities to the existing rows matching [scopeCondition].
   *
   * @param autoAccept If false, the recalculated nativities become pending ones for the user to
   *   promote, and the rows' existing nativities and overrides are left alone. If true, the
   *   recalculated nativities are accepted right away and any overrides are discarded.
   */
  private fun applyNativities(
      rows: List<Row4<SpeciesId?, LocalDate?, ExternalDatasetType?, SpeciesNativity?>>,
      scopeCondition: Condition,
      autoAccept: Boolean,
  ) {
    with(PROJECT_SPECIES) {
      val updateValues = DSL.values(*(rows.toTypedArray()))

      val speciesIdField = updateValues.field(0, SPECIES_ID.dataType)!!
      val datasetDateField = updateValues.field(1, PENDING_NATIVITY_DATASET_DATE.dataType)!!
      val datasetTypeField = updateValues.field(2, PENDING_NATIVITY_DATASET_TYPE_ID.dataType)!!
      val nativityField = updateValues.field(3, PENDING_NATIVITY_ID.dataType)!!

      val update =
          if (autoAccept) {
            dslContext
                .update(PROJECT_SPECIES)
                .set(CALCULATED_NATIVITY_DATASET_DATE, datasetDateField)
                .set(CALCULATED_NATIVITY_DATASET_TYPE_ID, datasetTypeField)
                .set(CALCULATED_NATIVITY_ID, nativityField)
                .setNull(OVERRIDDEN_BY)
                .setNull(OVERRIDDEN_JUSTIFICATION)
                .setNull(OVERRIDDEN_NATIVITY_ID)
                .setNull(OVERRIDDEN_TIME)
                .setNull(PENDING_NATIVITY_DATASET_DATE)
                .setNull(PENDING_NATIVITY_DATASET_TYPE_ID)
                .setNull(PENDING_NATIVITY_ID)
          } else {
            dslContext
                .update(PROJECT_SPECIES)
                .set(PENDING_NATIVITY_DATASET_DATE, datasetDateField)
                .set(PENDING_NATIVITY_DATASET_TYPE_ID, datasetTypeField)
                .set(PENDING_NATIVITY_ID, nativityField)
          }

      update.from(updateValues).where(scopeCondition).and(SPECIES_ID.eq(speciesIdField)).execute()
    }
  }

  /**
   * Inserts organization-level rows for species that don't have any [PROJECT_SPECIES] rows yet.
   * [autoAccept] chooses whether the nativities are accepted or pending, as in [applyNativities].
   */
  private fun insertNativities(
      organizationId: OrganizationId,
      speciesIds: Collection<SpeciesId>,
      nativities: Map<SpeciesId, SourcedSpeciesNativity>,
      autoAccept: Boolean,
  ) {
    val rows = speciesIds.map { speciesId ->
      val sourcedNativity = nativities[speciesId]

      DSL.row(
          organizationId,
          sourcedNativity?.datasetDate,
          sourcedNativity?.datasetType,
          sourcedNativity?.speciesNativity,
          speciesId,
      )
    }

    with(PROJECT_SPECIES) {
      val datasetDateField =
          if (autoAccept) CALCULATED_NATIVITY_DATASET_DATE else PENDING_NATIVITY_DATASET_DATE
      val datasetTypeField =
          if (autoAccept) CALCULATED_NATIVITY_DATASET_TYPE_ID else PENDING_NATIVITY_DATASET_TYPE_ID
      val nativityField = if (autoAccept) CALCULATED_NATIVITY_ID else PENDING_NATIVITY_ID

      dslContext
          .insertInto(
              PROJECT_SPECIES,
              ORGANIZATION_ID,
              datasetDateField,
              datasetTypeField,
              nativityField,
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
