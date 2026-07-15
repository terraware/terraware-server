package com.terraformation.backend.species.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.Row3
import org.jooq.impl.DSL

@Named
class ProjectSpeciesStore(
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
