package com.terraformation.backend.customer.model

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectsRow
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import java.time.Instant
import org.jooq.Record

data class ProjectModel<ID : ProjectId?>(
    val cohortId: CohortId? = null,
    val countryCode: String? = null,
    val createdBy: UserId? = null,
    val createdTime: Instant? = null,
    val description: String? = null,
    val id: ID,
    val modifiedBy: UserId? = null,
    val modifiedTime: Instant? = null,
    val name: String,
    val organizationId: OrganizationId,
    val phase: CohortPhase? = null,
) {
  companion object {
    fun of(row: ProjectsRow): ExistingProjectModel {
      return ExistingProjectModel(
          row.cohortId,
          row.countryCode,
          row.createdBy,
          row.createdTime,
          row.description,
          row.id!!,
          row.modifiedBy,
          row.modifiedTime,
          row.name!!,
          row.organizationId!!,
          row.phaseId,
      )
    }

    fun of(record: Record): ExistingProjectModel {
      return ExistingProjectModel(
          cohortId = record[PROJECTS.COHORT_ID],
          countryCode = record[PROJECTS.COUNTRY_CODE],
          createdBy = record[PROJECTS.CREATED_BY]!!,
          createdTime = record[PROJECTS.CREATED_TIME]!!,
          description = record[PROJECTS.DESCRIPTION],
          id = record[PROJECTS.ID]!!,
          modifiedBy = record[PROJECTS.MODIFIED_BY]!!,
          modifiedTime = record[PROJECTS.MODIFIED_TIME]!!,
          name = record[PROJECTS.NAME]!!,
          organizationId = record[PROJECTS.ORGANIZATION_ID]!!,
          phase = record[PROJECTS.PHASE_ID],
      )
    }
  }
}

typealias NewProjectModel = ProjectModel<Nothing?>

typealias ExistingProjectModel = ProjectModel<ProjectId>
