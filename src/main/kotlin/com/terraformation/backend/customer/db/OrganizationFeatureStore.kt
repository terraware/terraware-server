package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.OrganizationFeature
import com.terraformation.backend.customer.model.OrganizationFeatureModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.SEED_FUND_REPORTS
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** Store class to determine if an organization has access to certain features. */
@Named
class OrganizationFeatureStore(private val dslContext: DSLContext) {
  fun listOrganizationFeatureProjects(
      organizationId: OrganizationId
  ): Map<OrganizationFeature, OrganizationFeatureModel> {
    requirePermissions { readOrganizationFeatures(organizationId) }

    return dslContext
        .select(
            applicationProjectIdsField,
            deliverableProjectIdsField,
            moduleProjectIdsField,
            reportProjectIdsField,
            seedFundReportProjectIdsField,
            virtualWalkthroughField,
        )
        .from(ORGANIZATIONS)
        .where(ORGANIZATIONS.ID.eq(organizationId))
        .fetchOne { record ->
          listOf(
                  OrganizationFeatureModel.ofProjectIds(
                      OrganizationFeature.Applications,
                      record,
                      applicationProjectIdsField,
                  ),
                  OrganizationFeatureModel.ofProjectIds(
                      OrganizationFeature.Deliverables,
                      record,
                      deliverableProjectIdsField,
                  ),
                  OrganizationFeatureModel.ofProjectIds(
                      OrganizationFeature.Modules,
                      record,
                      moduleProjectIdsField,
                  ),
                  OrganizationFeatureModel.ofProjectIds(
                      OrganizationFeature.Reports,
                      record,
                      reportProjectIdsField,
                  ),
                  OrganizationFeatureModel.ofProjectIds(
                      OrganizationFeature.SeedFundReports,
                      record,
                      seedFundReportProjectIdsField,
                  ),
                  OrganizationFeatureModel.of(
                      OrganizationFeature.VirtualWalkthrough,
                      record,
                      virtualWalkthroughField,
                  ),
          ).associateBy { it.feature }
        } ?: emptyMap()
  }

  private val applicationProjectIdsField =
      DSL.multiset(
              DSL.select(PROJECTS.ID)
                  .from(APPLICATIONS)
                  .join(PROJECTS)
                  .on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
                  .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
          )
          .convertFrom { result -> result.map { record -> record[PROJECTS.ID] }.toSet() }

  private val deliverableProjectIdsField =
      DSL.multiset(
              DSL.select(PROJECTS.ID)
                  .from(DELIVERABLES)
                  .join(MODULES)
                  .on(MODULES.ID.eq(DELIVERABLES.MODULE_ID))
                  .leftJoin(SUBMISSIONS)
                  .on(SUBMISSIONS.DELIVERABLE_ID.eq(DELIVERABLES.ID))
                  .leftJoin(PROJECT_MODULES)
                  .on(MODULES.ID.eq(PROJECT_MODULES.MODULE_ID))
                  .leftJoin(PROJECTS)
                  .on(PROJECT_MODULES.PROJECT_ID.eq(PROJECTS.ID))
                  .or(PROJECTS.ID.eq(SUBMISSIONS.PROJECT_ID))
                  .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                  .and(
                      MODULES.PHASE_ID.notIn(
                          AcceleratorPhase.PreScreen,
                          AcceleratorPhase.Application,
                      )
                  )
          )
          .convertFrom { result -> result.map { record -> record[PROJECTS.ID] }.toSet() }

  private val moduleProjectIdsField =
      DSL.multiset(
              DSL.selectDistinct(PROJECTS.ID)
                  .from(PROJECT_MODULES)
                  .join(PROJECTS)
                  .on(PROJECT_MODULES.PROJECT_ID.eq(PROJECTS.ID))
                  .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
          )
          .convertFrom { result -> result.map { record -> record[PROJECTS.ID] }.toSet() }

  private val reportProjectIdsField =
      DSL.multiset(
              DSL.select(PROJECTS.ID)
                  .from(REPORTS)
                  .join(PROJECTS)
                  .on(PROJECTS.ID.eq(REPORTS.PROJECT_ID))
                  .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                  .and(REPORTS.STATUS_ID.notEqual(ReportStatus.NotNeeded))
          )
          .convertFrom { result -> result.map { record -> record[PROJECTS.ID] }.toSet() }

  private val seedFundReportProjectIdsField =
      DSL.multiset(
              DSL.select(PROJECTS.ID)
                  .from(SEED_FUND_REPORTS)
                  .join(PROJECTS)
                  .on(PROJECTS.ID.eq(SEED_FUND_REPORTS.PROJECT_ID))
                  .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
          )
          .convertFrom { result -> result.map { record -> record[PROJECTS.ID] }.toSet() }

  private val virtualWalkthroughField =
      with(ORGANIZATION_INTERNAL_TAGS) {
        DSL.exists(
            DSL.selectOne()
                .from(this)
                .where(ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                .and(INTERNAL_TAG_ID.eq(InternalTagId(4)))
        )
      }
}
