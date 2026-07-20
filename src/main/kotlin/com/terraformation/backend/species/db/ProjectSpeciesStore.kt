package com.terraformation.backend.species.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.species.model.ProjectSpeciesOverride
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.Row3
import org.jooq.impl.DSL

@Named
class ProjectSpeciesStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun assignProjects(assignments: Map<SpeciesId, Set<ProjectId>>) {
    require(assignments.isNotEmpty()) { "No species assignments specified" }

    val organizationId = checkOrganization(assignments)

    dslContext
        .insertInto(
            PROJECT_SPECIES,
            PROJECT_SPECIES.ORGANIZATION_ID,
            PROJECT_SPECIES.PROJECT_ID,
            PROJECT_SPECIES.SPECIES_ID,
        )
        .valuesOfRows(rowsFor(organizationId, assignments))
        .onConflictDoNothing()
        .execute()
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

    dslContext
        .deleteFrom(PROJECT_SPECIES)
        .where(
            DSL.row(
                    PROJECT_SPECIES.ORGANIZATION_ID,
                    PROJECT_SPECIES.PROJECT_ID,
                    PROJECT_SPECIES.SPECIES_ID,
                )
                .`in`(rowsFor(organizationId, assignments))
        )
        .execute()
  }

  private fun rowsFor(
      organizationId: OrganizationId,
      assignments: Map<SpeciesId, Set<ProjectId>>,
  ): List<Row3<OrganizationId?, ProjectId?, SpeciesId?>> =
      assignments.flatMap { (speciesId, projectIds) ->
        projectIds.map { projectId -> DSL.row(organizationId, projectId, speciesId) }
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
}
