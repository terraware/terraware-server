package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.default_schema.ProjectId
import java.time.LocalDate

data class ProjectReportConfigModel<ID : ProjectReportConfigId?>(
    val id: ID,
    val projectId: ProjectId,
    val frequency: ReportFrequency,
    val reportingStartDate: LocalDate,
    val reportingEndDate: LocalDate,
) {
  companion object {
    fun of(model: NewProjectReportConfigModel, id: ProjectReportConfigId) =
        ExistingProjectReportConfigModel(
            id,
            model.projectId,
            model.frequency,
            model.reportingStartDate,
            model.reportingEndDate,
        )
  }
}

typealias ExistingProjectReportConfigModel = ProjectReportConfigModel<ProjectReportConfigId>

typealias NewProjectReportConfigModel = ProjectReportConfigModel<Nothing?>
