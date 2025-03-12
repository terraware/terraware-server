package com.terraformation.backend.funder.model

import com.terraformation.backend.customer.model.SimpleProjectModel
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITIES
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class FundingEntityModel(
    val id: FundingEntityId,
    val name: String,
    val createdTime: Instant,
    val modifiedTime: Instant,
    val projects: List<ProjectId> = emptyList(),
) {
  companion object {
    fun of(
        record: Record,
        projectsField: Field<List<ProjectId>>? = null,
    ): FundingEntityModel {
      return FundingEntityModel(
          id = record[FUNDING_ENTITIES.ID]!!,
          name = record[FUNDING_ENTITIES.NAME]!!,
          createdTime = record[FUNDING_ENTITIES.CREATED_TIME]!!,
          modifiedTime = record[FUNDING_ENTITIES.MODIFIED_TIME]!!,
          projects = projectsField?.let { record[it] } ?: emptyList(),
      )
    }
  }
}

data class FundingEntityWithProjectsModel(
    val id: FundingEntityId,
    val name: String,
    val createdTime: Instant,
    val modifiedTime: Instant,
    val projects: List<SimpleProjectModel> = emptyList(),
)
