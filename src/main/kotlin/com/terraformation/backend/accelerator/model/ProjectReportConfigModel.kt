package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_REPORT_CONFIGS
import com.terraformation.backend.db.default_schema.ProjectId
import java.net.URI
import java.time.LocalDate
import org.jooq.Record

data class ProjectReportConfigModel<ConfigId : ProjectReportConfigId?>(
    val id: ConfigId,
    val projectId: ProjectId,
    val frequency: ReportFrequency,
    val reportingStartDate: LocalDate,
    val reportingEndDate: LocalDate,
    val logframeUrl: URI?,
) {
  companion object {
    fun of(model: NewProjectReportConfigModel, id: ProjectReportConfigId) =
        ExistingProjectReportConfigModel(
            id = id,
            projectId = model.projectId,
            frequency = model.frequency,
            reportingStartDate = model.reportingStartDate,
            reportingEndDate = model.reportingEndDate,
            logframeUrl = model.logframeUrl,
        )

    fun of(record: Record): ExistingProjectReportConfigModel =
        with(PROJECT_REPORT_CONFIGS) {
          ExistingProjectReportConfigModel(
              id = record[ID]!!,
              projectId = record[PROJECT_ID]!!,
              frequency = record[REPORT_FREQUENCY_ID]!!,
              reportingStartDate = record[REPORTING_START_DATE]!!,
              reportingEndDate = record[REPORTING_END_DATE]!!,
              logframeUrl = record[PROJECT_ACCELERATOR_DETAILS.LOGFRAME_URL],
          )
        }
  }
}

typealias ExistingProjectReportConfigModel = ProjectReportConfigModel<ProjectReportConfigId>

typealias NewProjectReportConfigModel = ProjectReportConfigModel<Nothing?>
