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

    val projectsMultiset =
        DSL.multiset(
                DSL.selectFrom(PROJECTS)
                    .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                    .and(PROJECTS.PARTICIPANT_ID.isNull)
                    .orderBy(PROJECTS.NAME))
            .convertFrom { result -> result.map { ProjectModel.of(it) } }

    return dslContext
        .select(ORGANIZATIONS.asterisk(), projectsMultiset)
        .from(ORGANIZATIONS)
        .whereExists(
            DSL.selectOne()
                .from(ORGANIZATION_INTERNAL_TAGS)
                .where(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                .and(ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.eq(InternalTagIds.Accelerator)))
        .andExists(
            DSL.selectOne()
                .from(PROJECTS)
                .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                .and(PROJECTS.PARTICIPANT_ID.isNull))
        .orderBy(ORGANIZATIONS.NAME)
        .fetch { OrganizationModel(it) to it[projectsMultiset] }
        .toMap()
  }

  /** Returns all the projects in organizations with the Accelerator internal tag. */
  fun findAll(): Map<OrganizationModel, List<ExistingProjectModel>> {
    requirePermissions { readInternalTags() }

    val projectsMultiset =
        DSL.multiset(
                DSL.selectFrom(PROJECTS)
                    .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                    .orderBy(PROJECTS.NAME))
            .convertFrom { result -> result.map { ProjectModel.of(it) } }

    return dslContext
        .select(ORGANIZATIONS.asterisk(), projectsMultiset)
        .from(ORGANIZATIONS)
        .whereExists(
            DSL.selectOne()
                .from(ORGANIZATION_INTERNAL_TAGS)
                .where(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                .and(ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.eq(InternalTagIds.Accelerator)))
        .orderBy(ORGANIZATIONS.NAME)
        .fetch { OrganizationModel(it) to it[projectsMultiset] }
        .toMap()
  }

  fun findAllWithProjectApplication(): Map<OrganizationModel, List<ExistingProjectModel>> {
    requirePermissions { readAllAcceleratorDetails() }

    val hasProjectApplicationCondition =
        DSL.exists(
            DSL.selectOne()
                .from(APPLICATIONS)
                .join(PROJECTS)
                .on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
                .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)))

    return fetchWithProjects(listOf(hasProjectApplicationCondition))
  }

  private fun fetchWithProjects(
      conditions: List<Condition>
  ): Map<OrganizationModel, List<ExistingProjectModel>> {
    if (conditions.isEmpty()) {
      return emptyMap()
    }

    val projectsMultiset =
        DSL.multiset(
                DSL.selectFrom(PROJECTS)
                    .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                    .and(PROJECTS.PARTICIPANT_ID.isNull)
                    .orderBy(PROJECTS.NAME))
            .convertFrom { result -> result.map { ProjectModel.of(it) } }

    return dslContext
        .select(ORGANIZATIONS.asterisk(), projectsMultiset)
        .from(ORGANIZATIONS)
        .where(conditions)
        .orderBy(ORGANIZATIONS.NAME)
        .fetch { OrganizationModel(it) to it[projectsMultiset] }
        .toMap()
  }
}
