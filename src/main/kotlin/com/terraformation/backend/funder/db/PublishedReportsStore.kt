package com.terraformation.backend.funder.db

import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_ACHIEVEMENTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_CHALLENGES
import com.terraformation.backend.funder.model.PublishedReportModel
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

@Named
class PublishedReportsStore(
    private val dslContext: DSLContext,
) {
  fun fetchPublishedReports(projectId: ProjectId): List<PublishedReportModel> {
    requirePermissions { readPublishedReports(projectId) }

    return dslContext
        .select(
            PUBLISHED_REPORTS.asterisk(),
            PROJECT_ACCELERATOR_DETAILS.DEAL_NAME,
            PROJECTS.NAME,
            achievementsMultiset,
            challengesMultiset)
        .from(PUBLISHED_REPORTS)
        .join(PROJECTS)
        .on(PUBLISHED_REPORTS.PROJECT_ID.eq(PROJECTS.ID))
        .leftJoin(PROJECT_ACCELERATOR_DETAILS)
        .on(PUBLISHED_REPORTS.PROJECT_ID.eq(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID))
        .where(PUBLISHED_REPORTS.PROJECT_ID.eq(projectId))
        .orderBy(PUBLISHED_REPORTS.END_DATE.desc())
        .fetch { record ->
          PublishedReportModel(
              achievements = record[achievementsMultiset],
              challenges = record[challengesMultiset],
              endDate = record[PUBLISHED_REPORTS.END_DATE]!!,
              frequency = record[PUBLISHED_REPORTS.REPORT_FREQUENCY_ID]!!,
              highlights = record[PUBLISHED_REPORTS.HIGHLIGHTS],
              projectId = record[PUBLISHED_REPORTS.PROJECT_ID]!!,
              projectName =
                  record[PROJECT_ACCELERATOR_DETAILS.DEAL_NAME] ?: record[PROJECTS.NAME]!!,
              publishedBy = record[PUBLISHED_REPORTS.PUBLISHED_BY]!!,
              publishedTime = record[PUBLISHED_REPORTS.PUBLISHED_TIME]!!,
              quarter = record[PUBLISHED_REPORTS.REPORT_QUARTER_ID],
              reportId = record[PUBLISHED_REPORTS.REPORT_ID]!!,
              startDate = record[PUBLISHED_REPORTS.START_DATE]!!)
        }
  }

  private val achievementsMultiset: Field<List<String>> =
      DSL.multiset(
              DSL.select(PUBLISHED_REPORT_ACHIEVEMENTS.ACHIEVEMENT)
                  .from(PUBLISHED_REPORT_ACHIEVEMENTS)
                  .where(PUBLISHED_REPORT_ACHIEVEMENTS.REPORT_ID.eq(PUBLISHED_REPORTS.REPORT_ID))
                  .orderBy(PUBLISHED_REPORT_ACHIEVEMENTS.POSITION))
          .convertFrom { result -> result.map { it[PUBLISHED_REPORT_ACHIEVEMENTS.ACHIEVEMENT]!! } }

  private val challengesMultiset: Field<List<ReportChallengeModel>> =
      DSL.multiset(
              DSL.select(
                      PUBLISHED_REPORT_CHALLENGES.CHALLENGE,
                      PUBLISHED_REPORT_CHALLENGES.MITIGATION_PLAN)
                  .from(PUBLISHED_REPORT_CHALLENGES)
                  .where(PUBLISHED_REPORT_CHALLENGES.REPORT_ID.eq(PUBLISHED_REPORTS.REPORT_ID))
                  .orderBy(PUBLISHED_REPORT_CHALLENGES.POSITION))
          .convertFrom { result ->
            result.map {
              ReportChallengeModel(
                  challenge = it[PUBLISHED_REPORT_CHALLENGES.CHALLENGE]!!,
                  mitigationPlan = it[PUBLISHED_REPORT_CHALLENGES.MITIGATION_PLAN]!!,
              )
            }
          }
}
