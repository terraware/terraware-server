package com.terraformation.backend.funder.db

import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportPhotoModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.MetricComponentConverter
import com.terraformation.backend.db.accelerator.MetricTypeConverter
import com.terraformation.backend.db.accelerator.ReportIdConverter
import com.terraformation.backend.db.accelerator.ReportMetricStatusConverter
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_METRICS
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.STANDARD_METRICS
import com.terraformation.backend.db.accelerator.tables.references.SYSTEM_METRICS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_ACHIEVEMENTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_CHALLENGES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_PHOTOS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_PROJECT_METRICS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_STANDARD_METRICS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_SYSTEM_METRICS
import com.terraformation.backend.funder.model.PublishedReportMetricModel
import com.terraformation.backend.funder.model.PublishedReportModel
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

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
            challengesMultiset,
            photosMultiset,
            projectMetricsMultiset,
            standardMetricsMultiset,
            systemMetricsMultiset,
        )
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
              additionalComments = record[PUBLISHED_REPORTS.ADDITIONAL_COMMENTS],
              challenges = record[challengesMultiset],
              endDate = record[PUBLISHED_REPORTS.END_DATE]!!,
              financialSummaries = record[PUBLISHED_REPORTS.FINANCIAL_SUMMARIES],
              frequency = record[PUBLISHED_REPORTS.REPORT_FREQUENCY_ID]!!,
              highlights = record[PUBLISHED_REPORTS.HIGHLIGHTS],
              photos = record[photosMultiset],
              projectId = record[PUBLISHED_REPORTS.PROJECT_ID]!!,
              projectMetrics = record[projectMetricsMultiset],
              projectName =
                  record[PROJECT_ACCELERATOR_DETAILS.DEAL_NAME] ?: record[PROJECTS.NAME]!!,
              publishedBy = record[PUBLISHED_REPORTS.PUBLISHED_BY]!!,
              publishedTime = record[PUBLISHED_REPORTS.PUBLISHED_TIME]!!,
              quarter = record[PUBLISHED_REPORTS.REPORT_QUARTER_ID],
              reportId = record[PUBLISHED_REPORTS.REPORT_ID]!!,
              startDate = record[PUBLISHED_REPORTS.START_DATE]!!,
              standardMetrics = record[standardMetricsMultiset],
              systemMetrics = record[systemMetricsMultiset],
          )
        }
  }

  private val achievementsMultiset: Field<List<String>> =
      DSL.multiset(
              DSL.select(PUBLISHED_REPORT_ACHIEVEMENTS.ACHIEVEMENT)
                  .from(PUBLISHED_REPORT_ACHIEVEMENTS)
                  .where(PUBLISHED_REPORT_ACHIEVEMENTS.REPORT_ID.eq(PUBLISHED_REPORTS.REPORT_ID))
                  .orderBy(PUBLISHED_REPORT_ACHIEVEMENTS.POSITION)
          )
          .convertFrom { result -> result.map { it[PUBLISHED_REPORT_ACHIEVEMENTS.ACHIEVEMENT]!! } }

  private val challengesMultiset: Field<List<ReportChallengeModel>> =
      DSL.multiset(
              DSL.select(
                      PUBLISHED_REPORT_CHALLENGES.CHALLENGE,
                      PUBLISHED_REPORT_CHALLENGES.MITIGATION_PLAN,
                  )
                  .from(PUBLISHED_REPORT_CHALLENGES)
                  .where(PUBLISHED_REPORT_CHALLENGES.REPORT_ID.eq(PUBLISHED_REPORTS.REPORT_ID))
                  .orderBy(PUBLISHED_REPORT_CHALLENGES.POSITION)
          )
          .convertFrom { result ->
            result.map {
              ReportChallengeModel(
                  challenge = it[PUBLISHED_REPORT_CHALLENGES.CHALLENGE]!!,
                  mitigationPlan = it[PUBLISHED_REPORT_CHALLENGES.MITIGATION_PLAN]!!,
              )
            }
          }

  private fun <ID : Any> publishedMetricsMultiset(
      metricTableIdField: TableField<*, ID?>,
      publishedMetricIdField: TableField<*, ID?>,
  ): Field<List<PublishedReportMetricModel<ID>>> {
    val publishedMetricTable = publishedMetricIdField.table!!
    val reportIdField =
        publishedMetricTable.field(
            "report_id",
            SQLDataType.BIGINT.asConvertedDataType(ReportIdConverter()),
        )!!
    val progressNotesField = publishedMetricTable.field("progress_notes", String::class.java)
    val statusField =
        publishedMetricTable.field(
            "status_id",
            SQLDataType.INTEGER.asConvertedDataType(ReportMetricStatusConverter()),
        )!!
    val targetField = publishedMetricTable.field("target", Int::class.java)!!
    val underperformanceJustificationField =
        publishedMetricTable.field("underperformance_justification", String::class.java)!!
    val valueField = publishedMetricTable.field("value", Int::class.java)!!

    val metricTable = metricTableIdField.table!!
    val metricComponentField =
        metricTable.field(
            "component_id",
            SQLDataType.INTEGER.asConvertedDataType(MetricComponentConverter()),
        )!!
    val metricDescriptionField = metricTable.field("description", String::class.java)!!
    val metricNameField = metricTable.field("name", String::class.java)!!
    val metricReferenceField = metricTable.field("reference", String::class.java)!!
    val metricTypeField =
        metricTable.field(
            "type_id",
            SQLDataType.INTEGER.asConvertedDataType(MetricTypeConverter()),
        )!!
    val unitField = metricTable.field("unit", String::class.java) ?: DSL.value(null as String?)

    return DSL.multiset(
            DSL.select(
                    progressNotesField,
                    publishedMetricIdField,
                    metricComponentField,
                    metricDescriptionField,
                    metricNameField,
                    metricReferenceField,
                    metricTypeField,
                    statusField,
                    targetField,
                    underperformanceJustificationField,
                    valueField,
                    unitField,
                )
                .from(publishedMetricTable)
                .join(metricTable)
                .on(metricTableIdField.eq(publishedMetricIdField))
                .where(reportIdField.eq(PUBLISHED_REPORTS.REPORT_ID))
                .orderBy(metricReferenceField)
        )
        .convertFrom { result ->
          result.map {
            PublishedReportMetricModel(
                component = it[metricComponentField],
                description = it[metricDescriptionField],
                metricId = it[publishedMetricIdField.asNonNullable()],
                name = it[metricNameField],
                progressNotes = it[progressNotesField],
                reference = it[metricReferenceField],
                status = it[statusField],
                target = it[targetField],
                type = it[metricTypeField],
                underperformanceJustification = it[underperformanceJustificationField],
                value = it[valueField],
                unit = it[unitField],
            )
          }
        }
  }

  private val photosMultiset: Field<List<ReportPhotoModel>> =
      DSL.multiset(
              DSL.select(
                      PUBLISHED_REPORT_PHOTOS.CAPTION,
                      PUBLISHED_REPORT_PHOTOS.FILE_ID,
                  )
                  .from(PUBLISHED_REPORT_PHOTOS)
                  .where(PUBLISHED_REPORT_PHOTOS.REPORT_ID.eq(REPORTS.ID))
                  .orderBy(PUBLISHED_REPORT_PHOTOS.FILE_ID)
          )
          .convertFrom { results -> results.map { ReportPhotoModel.ofPublished(it) } }

  private val projectMetricsMultiset =
      publishedMetricsMultiset(
          PROJECT_METRICS.ID,
          PUBLISHED_REPORT_PROJECT_METRICS.PROJECT_METRIC_ID,
      )

  private val standardMetricsMultiset =
      publishedMetricsMultiset(
          STANDARD_METRICS.ID,
          PUBLISHED_REPORT_STANDARD_METRICS.STANDARD_METRIC_ID,
      )

  private val systemMetricsMultiset =
      publishedMetricsMultiset(
          SYSTEM_METRICS.ID,
          PUBLISHED_REPORT_SYSTEM_METRICS.SYSTEM_METRIC_ID,
      )
}
