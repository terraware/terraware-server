package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import java.time.Instant
import org.jooq.Record
import org.locationtech.jts.geom.Geometry

data class ExistingApplicationModel(
    val boundary: Geometry? = null,
    val createdTime: Instant,
    val feedback: String? = null,
    val id: ApplicationId,
    val internalComment: String? = null,
    val internalName: String? = null,
    val organizationId: OrganizationId,
    val projectId: ProjectId,
    val status: ApplicationStatus,
) {
  companion object {
    fun of(record: Record): ExistingApplicationModel {
      return with(APPLICATIONS) {
        ExistingApplicationModel(
            boundary = record[BOUNDARY],
            createdTime = record[CREATED_TIME]!!,
            feedback = record[FEEDBACK],
            id = record[ID]!!,
            internalComment = record[INTERNAL_COMMENT],
            internalName = record[INTERNAL_NAME],
            organizationId = record[projects.ORGANIZATION_ID]!!,
            projectId = record[PROJECT_ID]!!,
            status = record[APPLICATION_STATUS_ID]!!,
        )
      }
    }
  }
}
