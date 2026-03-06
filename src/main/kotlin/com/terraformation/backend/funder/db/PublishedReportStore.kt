package com.terraformation.backend.funder.db

import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportPhotoModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.IndicatorCategoryConverter
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorClassConverter
import com.terraformation.backend.db.accelerator.IndicatorLevelConverter
import com.terraformation.backend.db.accelerator.ReportIdConverter
import com.terraformation.backend.db.accelerator.ReportIndicatorStatusConverter
import com.terraformation.backend.db.accelerator.tables.references.AUTO_CALCULATED_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.COMMON_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_AUTO_CALCULATED_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_COMMON_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_INDICATORS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ProjectIdConverter
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_AUTO_CALCULATED_INDICATOR_BASELINES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_AUTO_CALCULATED_INDICATOR_TARGETS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_COMMON_INDICATOR_BASELINES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_COMMON_INDICATOR_TARGETS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_PROJECT_INDICATOR_BASELINES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_PROJECT_INDICATOR_TARGETS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_ACHIEVEMENTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_AUTO_CALCULATED_INDICATORS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_CHALLENGES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_COMMON_INDICATORS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_PHOTOS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_PROJECT_INDICATORS
import com.terraformation.backend.funder.model.PublishedCumulativeIndicatorProgressModel
import com.terraformation.backend.funder.model.PublishedReportIndicatorModel
import com.terraformation.backend.funder.model.PublishedReportModel
import jakarta.inject.Named
import java.math.BigDecimal
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

@Named
class PublishedReportStore(
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
            projectIndicatorsMultiset,
            commonIndicatorsMultiset,
            autoCalculatedIndicatorsMultiset,
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
              highlights = record[PUBLISHED_REPORTS.HIGHLIGHTS],
              photos = record[photosMultiset],
              projectId = record[PUBLISHED_REPORTS.PROJECT_ID]!!,
              projectIndicators = record[projectIndicatorsMultiset],
              projectName =
                  record[PROJECT_ACCELERATOR_DETAILS.DEAL_NAME] ?: record[PROJECTS.NAME]!!,
              publishedBy = record[PUBLISHED_REPORTS.PUBLISHED_BY]!!,
              publishedTime = record[PUBLISHED_REPORTS.PUBLISHED_TIME]!!,
              quarter = record[PUBLISHED_REPORTS.REPORT_QUARTER_ID],
              reportId = record[PUBLISHED_REPORTS.REPORT_ID]!!,
              startDate = record[PUBLISHED_REPORTS.START_DATE]!!,
              commonIndicators = record[commonIndicatorsMultiset],
              autoCalculatedIndicators = record[autoCalculatedIndicatorsMultiset],
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

  private fun <ID : Any> publishedIndicatorsMultiset(
      indicatorTableIdField: TableField<*, ID?>,
      publishedIndicatorIdField: TableField<*, ID?>,
      targetTableIndicatorIdField: TableField<*, ID?>,
      baselineTableIndicatorIdField: TableField<*, ID?>,
      reportTableIndicatorIdField: TableField<*, ID?>,
  ): Field<List<PublishedReportIndicatorModel<ID>>> {
    val publishedIndicatorTable = publishedIndicatorIdField.table!!
    val reportIdField =
        publishedIndicatorTable.field(
            "report_id",
            SQLDataType.BIGINT.asConvertedDataType(ReportIdConverter()),
        )!!
    val progressNotesField = publishedIndicatorTable.field("progress_notes", String::class.java)
    val statusField =
        publishedIndicatorTable.field(
            "status_id",
            SQLDataType.INTEGER.asConvertedDataType(ReportIndicatorStatusConverter()),
        )!!
    val projectsCommentsField =
        publishedIndicatorTable.field("projects_comments", String::class.java)!!
    val valueField = publishedIndicatorTable.field("value", Int::class.java)!!

    val targetTable = targetTableIndicatorIdField.table!!
    val targetField = targetTable.field("target", Int::class.java)!!
    val targetYearField = targetTable.field("year", Int::class.java)!!
    val targetProjectIdField =
        targetTable.field(
            "project_id",
            SQLDataType.BIGINT.asConvertedDataType(ProjectIdConverter()),
        )!!

    val baselineTable = baselineTableIndicatorIdField.table!!
    val baselineProjectIdField =
        baselineTable.field(
            "project_id",
            SQLDataType.BIGINT.asConvertedDataType(ProjectIdConverter()),
        )!!
    val baselineField = baselineTable.field("baseline", BigDecimal::class.java)!!
    val endTargetField = baselineTable.field("end_target", BigDecimal::class.java)!!

    val indicatorTable = indicatorTableIdField.table!!
    val indicatorCategoryField =
        indicatorTable.field(
            "category_id",
            SQLDataType.INTEGER.asConvertedDataType(IndicatorCategoryConverter()),
        )!!
    val indicatorClassField =
        indicatorTable.field(
            "class_id",
            SQLDataType.INTEGER.asConvertedDataType(IndicatorClassConverter()),
        )!!
    val indicatorDescriptionField = indicatorTable.field("description", String::class.java)!!
    val indicatorNameField = indicatorTable.field("name", String::class.java)!!
    val indicatorReferenceField = indicatorTable.field("ref_id", String::class.java)!!
    val indicatorTypeField =
        indicatorTable.field(
            "level_id",
            SQLDataType.INTEGER.asConvertedDataType(IndicatorLevelConverter()),
        )!!
    val unitField = indicatorTable.field("unit", String::class.java) ?: DSL.value(null as String?)

    val reportIndicatorTable = reportTableIndicatorIdField.table!!
    val reportIndicatorReportField =
        reportIndicatorTable.field(
            "report_id",
            SQLDataType.BIGINT.asConvertedDataType(ReportIdConverter()),
        )!!
    val reportValueField =
        reportIndicatorTable.field("value", Int::class.java)
            ?: DSL.coalesce(
                reportIndicatorTable.field("override_value", Int::class.java)!!,
                reportIndicatorTable.field("system_value", Int::class.java)!!,
            )
    val reportsForSum = REPORTS.`as`("reportsForSum")
    val sumAtPreviousYearEnd =
        DSL.`when`(
            indicatorClassField.eq(IndicatorClass.Cumulative),
            DSL.select(DSL.sum(reportValueField))
                .from(reportIndicatorTable)
                .join(reportsForSum)
                .on(reportsForSum.ID.eq(reportIndicatorReportField))
                .where(DSL.year(reportsForSum.END_DATE).lt(DSL.year(PUBLISHED_REPORTS.END_DATE)))
                .and(reportsForSum.PROJECT_ID.eq(PUBLISHED_REPORTS.PROJECT_ID))
                .and(reportTableIndicatorIdField.eq(indicatorTableIdField)),
        )

    val reportsForProgress = REPORTS.`as`("reportsForProgress")
    val reportIndicatorsForProgress = reportIndicatorTable.`as`("reportIndicatorsForProgress")
    val indicatorIdForProgress = reportIndicatorsForProgress.field(reportTableIndicatorIdField)!!
    val indicatorValueProgressField =
        reportIndicatorsForProgress.field("value", Int::class.java)
            ?: DSL.coalesce(
                reportIndicatorsForProgress.field("override_value", Int::class.java)!!,
                reportIndicatorsForProgress.field("system_value", Int::class.java)!!,
            )
    val reportIdProgressField =
        reportIndicatorsForProgress.field(
            "report_id",
            SQLDataType.BIGINT.asConvertedDataType(ReportIdConverter()),
        )!!
    val publishedIndicatorForProgress =
        publishedIndicatorTable.`as`("publishedIndicatorForProgress")
    val publishedReportIdForProgressField =
        publishedIndicatorForProgress.field(
            "report_id",
            SQLDataType.BIGINT.asConvertedDataType(ReportIdConverter()),
        )!!
    val publishedIndicatorIdForProgress =
        publishedIndicatorForProgress.field(publishedIndicatorIdField)!!
    val publishedValueForProgress = publishedIndicatorForProgress.field("value", Int::class.java)!!
    // For previous quarters, use the report value directly. For the current quarter, use the
    // published value only (null if no published entry, which excludes it from the result).
    val progressValue =
        DSL.if_(
            reportsForProgress.REPORT_QUARTER_ID.eq(PUBLISHED_REPORTS.REPORT_QUARTER_ID),
            publishedValueForProgress,
            indicatorValueProgressField,
        )

    val currentYearProgressField: Field<List<PublishedCumulativeIndicatorProgressModel>> =
        DSL.multiset(
                DSL.select(
                        reportsForProgress.REPORT_QUARTER_ID,
                        progressValue.`as`("progress_value"),
                    )
                    .from(reportIndicatorsForProgress)
                    .join(reportsForProgress)
                    .on(reportsForProgress.ID.eq(reportIdProgressField))
                    .leftJoin(publishedIndicatorForProgress)
                    .on(
                        publishedReportIdForProgressField
                            .eq(reportIdProgressField)
                            .and(publishedIndicatorIdForProgress.eq(indicatorTableIdField))
                            .and(publishedReportIdForProgressField.eq(PUBLISHED_REPORTS.REPORT_ID))
                    )
                    .where(
                        DSL.year(reportsForProgress.END_DATE)
                            .eq(DSL.year(PUBLISHED_REPORTS.END_DATE))
                    )
                    .and(reportsForProgress.PROJECT_ID.eq(PUBLISHED_REPORTS.PROJECT_ID))
                    .and(indicatorIdForProgress.eq(indicatorTableIdField))
                    .and(progressValue.isNotNull)
                    .and(
                        reportsForProgress.REPORT_QUARTER_ID.le(PUBLISHED_REPORTS.REPORT_QUARTER_ID)
                    )
                    .orderBy(reportsForProgress.REPORT_QUARTER_ID)
            )
            .convertFrom { results ->
              results.map { record ->
                PublishedCumulativeIndicatorProgressModel(
                    quarter = record[reportsForProgress.REPORT_QUARTER_ID]!!,
                    value = record[DSL.field("progress_value", SQLDataType.INTEGER)]!!,
                )
              }
            }

    val reportIndicatorForReport = reportIndicatorTable.`as`("reportIndicatorForReport")
    val repIndicatorIdForReport = reportIndicatorForReport.field(reportTableIndicatorIdField)!!
    val repReportIdForReport =
        reportIndicatorForReport.field(
            "report_id",
            SQLDataType.BIGINT.asConvertedDataType(ReportIdConverter()),
        )!!

    return DSL.multiset(
            DSL.select(
                    progressNotesField,
                    projectsCommentsField,
                    indicatorTableIdField,
                    indicatorCategoryField,
                    indicatorClassField,
                    indicatorDescriptionField,
                    indicatorNameField,
                    indicatorReferenceField,
                    indicatorTypeField,
                    statusField,
                    targetField,
                    valueField,
                    unitField,
                    baselineField,
                    endTargetField,
                    sumAtPreviousYearEnd,
                    currentYearProgressField,
                )
                .from(indicatorTable)
                .leftJoin(publishedIndicatorTable)
                .on(
                    publishedIndicatorIdField
                        .eq(indicatorTableIdField)
                        .and(reportIdField.eq(PUBLISHED_REPORTS.REPORT_ID))
                )
                .leftJoin(reportIndicatorForReport)
                .on(
                    repIndicatorIdForReport
                        .eq(indicatorTableIdField)
                        .and(repReportIdForReport.eq(PUBLISHED_REPORTS.REPORT_ID))
                )
                .leftJoin(targetTable)
                .on(
                    targetTableIndicatorIdField
                        .eq(indicatorTableIdField)
                        .and(targetProjectIdField.eq(PUBLISHED_REPORTS.PROJECT_ID))
                        .and(targetYearField.eq(DSL.year(PUBLISHED_REPORTS.END_DATE)))
                )
                .leftJoin(baselineTable)
                .on(
                    baselineTableIndicatorIdField
                        .eq(indicatorTableIdField)
                        .and(baselineProjectIdField.eq(PUBLISHED_REPORTS.PROJECT_ID))
                )
                .where(publishedIndicatorIdField.isNotNull.or(repIndicatorIdForReport.isNotNull))
                .orderBy(indicatorReferenceField)
        )
        .convertFrom { result ->
          result.map {
            PublishedReportIndicatorModel(
                baseline = it[baselineField],
                category = it[indicatorCategoryField],
                classId = it[indicatorClassField],
                currentYearProgress = it[currentYearProgressField],
                description = it[indicatorDescriptionField],
                endOfProjectTarget = it[endTargetField],
                indicatorId = it[indicatorTableIdField.asNonNullable()],
                level = it[indicatorTypeField],
                name = it[indicatorNameField],
                previousYearCumulativeTotal = it[sumAtPreviousYearEnd],
                progressNotes = it[progressNotesField],
                projectsComments = it[projectsCommentsField],
                refId = it[indicatorReferenceField],
                status = it[statusField],
                target = it[targetField],
                unit = it[unitField],
                value = it[valueField],
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
                  .where(PUBLISHED_REPORT_PHOTOS.REPORT_ID.eq(PUBLISHED_REPORTS.REPORT_ID))
                  .orderBy(PUBLISHED_REPORT_PHOTOS.FILE_ID)
          )
          .convertFrom { results -> results.map { ReportPhotoModel.ofPublished(it) } }

  private val projectIndicatorsMultiset =
      publishedIndicatorsMultiset(
          PROJECT_INDICATORS.ID,
          PUBLISHED_REPORT_PROJECT_INDICATORS.PROJECT_INDICATOR_ID,
          PUBLISHED_PROJECT_INDICATOR_TARGETS.PROJECT_INDICATOR_ID,
          PUBLISHED_PROJECT_INDICATOR_BASELINES.PROJECT_INDICATOR_ID,
          REPORT_PROJECT_INDICATORS.PROJECT_INDICATOR_ID,
      )

  private val commonIndicatorsMultiset =
      publishedIndicatorsMultiset(
          COMMON_INDICATORS.ID,
          PUBLISHED_REPORT_COMMON_INDICATORS.COMMON_INDICATOR_ID,
          PUBLISHED_COMMON_INDICATOR_TARGETS.COMMON_INDICATOR_ID,
          PUBLISHED_COMMON_INDICATOR_BASELINES.COMMON_INDICATOR_ID,
          REPORT_COMMON_INDICATORS.COMMON_INDICATOR_ID,
      )

  private val autoCalculatedIndicatorsMultiset =
      publishedIndicatorsMultiset(
          AUTO_CALCULATED_INDICATORS.ID,
          PUBLISHED_REPORT_AUTO_CALCULATED_INDICATORS.AUTO_CALCULATED_INDICATOR_ID,
          PUBLISHED_AUTO_CALCULATED_INDICATOR_TARGETS.AUTO_CALCULATED_INDICATOR_ID,
          PUBLISHED_AUTO_CALCULATED_INDICATOR_BASELINES.AUTO_CALCULATED_INDICATOR_ID,
          REPORT_AUTO_CALCULATED_INDICATORS.AUTO_CALCULATED_INDICATOR_ID,
      )
}
