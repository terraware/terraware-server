package com.terraformation.backend.accelerator.db

import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.DSL.multiset
import org.jooq.impl.DSL.selectFrom

@Named
class AcceleratorOrganizationStore(private val dslContext: DSLContext) {
  /**
   * Returns all the projects in organizations that have projects in the accelerator or with an
   * accelerator application.
   */
  fun findAll(): Map<OrganizationModel, List<ExistingProjectModel>> {
    requirePermissions { readInternalTags() }
    requirePermissions { readAllAcceleratorDetails() }

    val projectsMultiset =
        multiset(
                selectFrom(PROJECTS)
                    .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                    .orderBy(PROJECTS.NAME)
            )
            .convertFrom { result -> result.map { ProjectModel.of(it) } }

    return dslContext
        .select(ORGANIZATIONS.asterisk(), projectsMultiset)
        .from(ORGANIZATIONS)
        .where(
            DSL.exists(
                DSL.selectOne()
                    .from(PROJECTS)
                    .leftJoin(APPLICATIONS)
                    .on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
                    .where(PROJECTS.PHASE_ID.isNotNull.or(APPLICATIONS.PROJECT_ID.isNotNull))
                    .and(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
            )
        )
        .orderBy(ORGANIZATIONS.NAME)
        .fetch { OrganizationModel(it) to it[projectsMultiset] }
        .toMap()
  }
}
