package com.terraformation.backend.funder.model

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_PROJECT_DETAILS
import org.jooq.Record

data class PublishedProjectNameModel(
    val dealName: String? = null,
    val projectId: ProjectId,
) {
  companion object {
    fun of(record: Record): PublishedProjectNameModel {
      return PublishedProjectNameModel(
          dealName = record[PUBLISHED_PROJECT_DETAILS.DEAL_NAME],
          projectId = record[PUBLISHED_PROJECT_DETAILS.PROJECT_ID]!!,
      )
    }
  }
}
