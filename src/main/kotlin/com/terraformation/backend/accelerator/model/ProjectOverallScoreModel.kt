package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.tables.records.ProjectOverallScoresRecord
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_OVERALL_SCORES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import java.net.URI
import java.time.Instant

data class ProjectOverallScoreModel(
    val detailsUrl: URI? = null,
    val overallScore: Double? = null,
    val projectId: ProjectId,
    val summary: String? = null,
    val modifiedBy: UserId? = null,
    val modifiedTime: Instant? = null,
) {
  companion object {
    fun of(record: ProjectOverallScoresRecord): ProjectOverallScoreModel {
      return with(PROJECT_OVERALL_SCORES) {
        ProjectOverallScoreModel(
            detailsUrl = record[DETAILS_URL],
            overallScore = record[OVERALL_SCORE],
            projectId = record[PROJECT_ID]!!,
            summary = record[SUMMARY],
            modifiedBy = record[MODIFIED_BY],
            modifiedTime = record[MODIFIED_TIME],
        )
      }
    }
  }
}
