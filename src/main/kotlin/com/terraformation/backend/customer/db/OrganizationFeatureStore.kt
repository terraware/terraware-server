package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.OrganizationFeature
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
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
  ): Map<OrganizationFeature, Set<ProjectId>> {
    requirePermissions { readOrganizationFeatures(organizationId) }

    return dslContext
        .select(
            applicationsExistsField,
            deliverablesExistsField,
            modulesExistsField,
            reportsExistsField,
            seedFundReportsExistsField,
        )
        .from(ORGANIZATIONS)
        .where(ORGANIZATIONS.ID.eq(organizationId))
        .fetchOne { record ->
          mapOf(
              OrganizationFeature.Applications to record[applicationsExistsField],
              OrganizationFeature.Deliverables to record[deliverablesExistsField],
              OrganizationFeature.Modules to record[modulesExistsField],
              OrganizationFeature.Reports to record[reportsExistsField],
              OrganizationFeature.SeedFundReports to record[seedFundReportsExistsField],
          )
        } ?: emptyMap()
  }

  private val applicationsExistsField =
      DSL.multiset(
              DSL.select(PROJECTS.ID)
                  .from(APPLICATIONS)
                  .join(PROJECTS)
                  .on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
                  .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)))
          .convertFrom { result -> result.map { record -> record[PROJECTS.ID] }.toSet() }

  private val deliverablesExistsField =
      DSL.multiset(
              DSL.select(PROJECTS.ID)
                  .from(DELIVERABLES)
                  .join(MODULES)
                  .on(MODULES.ID.eq(DELIVERABLES.MODULE_ID))
                  .leftJoin(SUBMISSIONS)
                  .on(SUBMISSIONS.DELIVERABLE_ID.eq(DELIVERABLES.ID))
                  .leftJoin(COHORT_MODULES)
                  .on(COHORT_MODULES.MODULE_ID.eq(DELIVERABLES.MODULE_ID))
                  .leftJoin(PARTICIPANTS)
                  .on(PARTICIPANTS.COHORT_ID.eq(COHORT_MODULES.COHORT_ID))
                  .join(PROJECTS)
                  .on(PROJECTS.PARTICIPANT_ID.eq(PARTICIPANTS.ID))
                  .or(PROJECTS.ID.eq(SUBMISSIONS.PROJECT_ID))
                  .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                  .and(MODULES.PHASE_ID.notIn(CohortPhase.PreScreen, CohortPhase.Application)))
          .convertFrom { result -> result.map { record -> record[PROJECTS.ID] }.toSet() }

  private val modulesExistsField =
      DSL.multiset(
              DSL.select(PROJECTS.ID)
                  .from(COHORT_MODULES)
                  .join(PARTICIPANTS)
                  .on(PARTICIPANTS.COHORT_ID.eq(COHORT_MODULES.COHORT_ID))
                  .join(PROJECTS)
                  .on(PROJECTS.PARTICIPANT_ID.eq(PARTICIPANTS.ID))
                  .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)))
          .convertFrom { result -> result.map { record -> record[PROJECTS.ID] }.toSet() }

  private val reportsExistsField =
      DSL.multiset(
              DSL.select(PROJECTS.ID)
                  .from(REPORTS)
                  .join(PROJECTS)
                  .on(PROJECTS.ID.eq(REPORTS.PROJECT_ID))
                  .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                  .and(REPORTS.STATUS_ID.notEqual(ReportStatus.NotNeeded)))
          .convertFrom { result -> result.map { record -> record[PROJECTS.ID] }.toSet() }

  private val seedFundReportsExistsField =
      DSL.multiset(
              DSL.select(PROJECTS.ID)
                  .from(SEED_FUND_REPORTS)
                  .join(PROJECTS)
                  .on(PROJECTS.ID.eq(SEED_FUND_REPORTS.PROJECT_ID))
                  .where(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)))
          .convertFrom { result -> result.map { record -> record[PROJECTS.ID] }.toSet() }
}
