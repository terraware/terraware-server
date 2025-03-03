package com.terraformation.backend.funder.model

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
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
    val projects: List<FundingEntityProjectModel>? = emptyList(),
) {
  constructor(
      record: Record,
      projectsMultiset: Field<List<FundingEntityProjectModel>>? = null,
  ) : this(
      id = record[FUNDING_ENTITIES.ID]!!,
      name = record[FUNDING_ENTITIES.NAME]!!,
      createdTime = record[FUNDING_ENTITIES.CREATED_TIME]!!,
      modifiedTime = record[FUNDING_ENTITIES.MODIFIED_TIME]!!,
      projects = projectsMultiset?.let { record[it] })
}

data class FundingEntityProjectModel(
    val id: ProjectId,
    val name: String,
    val description: String?,
) {
  constructor(
      record: Record,
  ) : this(
      id = record[PROJECTS.ID]!!,
      name = record[PROJECTS.NAME]!!,
      description = record[PROJECTS.DESCRIPTION])
}
