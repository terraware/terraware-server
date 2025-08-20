package com.terraformation.backend.accelerator.db

import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class AcceleratorOrganizationStore(private val dslContext: DSLContext) {
  /**
   * Returns all the projects in organizations with the Accelerator internal tag that have not been
   * assigned to participants yet.
   */
  fun fetchWithUnassignedProjects(): Map<OrganizationModel, List<ExistingProjectModel>> {
    requirePermissions { readInternalTags() }

    val hasParticipantProject =
        DSL.exists(
            DSL.selectOne()
                .from(PROJECTS)
                .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                .and(PROJECTS.PARTICIPANT_ID.isNull)
        )

    val isUnassigned = PROJECTS.PARTICIPANT_ID.isNull

    return fetchWithProjects(
        listOf(hasAcceleratorTagCondition, hasParticipantProject),
        listOf(isUnassigned),
    )
  }

  /**
   * Returns all the projects in organizations with either the Accelerator internal tag or those
   * that have an application
   */
  fun findAll(): Map<OrganizationModel, List<ExistingProjectModel>> {
    requirePermissions { readInternalTags() }
    requirePermissions { readAllAcceleratorDetails() }

    return fetchWithProjects(listOf(hasAcceleratorTagCondition.or(hasProjectApplicationCondition)))
  }

  /** Returns all the projects in organizations with the Accelerator internal tag. */
  fun findAllWithAcceleratorTag(): Map<OrganizationModel, List<ExistingProjectModel>> {
    requirePermissions { readInternalTags() }

    return fetchWithProjects(listOf(hasAcceleratorTagCondition))
  }

  fun findAllWithProjectApplication(): Map<OrganizationModel, List<ExistingProjectModel>> {
    requirePermissions { readAllAcceleratorDetails() }

    return fetchWithProjects(listOf(hasProjectApplicationCondition))
  }

  private fun fetchWithProjects(
      orgConditions: List<Condition>,
      projectConditions: List<Condition> = emptyList(),
  ): Map<OrganizationModel, List<ExistingProjectModel>> {
    if (orgConditions.isEmpty()) {
      return emptyMap()
    }

    val projectsMultiset =
        DSL.multiset(
                DSL.selectFrom(PROJECTS)
                    .where(projectConditions + PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                    .orderBy(PROJECTS.NAME)
            )
            .convertFrom { result -> result.map { ProjectModel.of(it) } }

    return dslContext
        .select(ORGANIZATIONS.asterisk(), projectsMultiset)
        .from(ORGANIZATIONS)
        .where(orgConditions)
        .orderBy(ORGANIZATIONS.NAME)
        .fetch { OrganizationModel(it) to it[projectsMultiset] }
        .toMap()
  }

  private val hasAcceleratorTagCondition =
      DSL.exists(
          DSL.selectOne()
              .from(ORGANIZATION_INTERNAL_TAGS)
              .where(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
              .and(ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.eq(InternalTagIds.Accelerator))
      )

  private val hasProjectApplicationCondition =
      DSL.exists(
          DSL.selectOne()
              .from(APPLICATIONS)
              .join(PROJECTS)
              .on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
              .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
      )
}
