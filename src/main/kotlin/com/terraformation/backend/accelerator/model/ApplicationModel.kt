package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import java.time.Instant
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.Geometry

data class ExistingApplicationModel(
    val boundary: Geometry? = null,
    val countryCode: String? = null,
    val createdTime: Instant,
    val feedback: String? = null,
    val id: ApplicationId,
    val internalComment: String? = null,
    val internalName: String,
    val modifiedTme: Instant?,
    val organizationId: OrganizationId,
    val organizationName: String,
    val projectId: ProjectId,
    val projectName: String,
    val status: ApplicationStatus,
) {
  companion object {
    fun of(
        record: Record,
        modifiedTimeField: Field<Instant?>? = null,
    ): ExistingApplicationModel {
      return with(APPLICATIONS) {
        ExistingApplicationModel(
            boundary = record[BOUNDARY],
            countryCode = record[COUNTRY_CODE],
            createdTime = record[CREATED_TIME]!!,
            feedback = record[FEEDBACK],
            id = record[ID]!!,
            internalComment = record[INTERNAL_COMMENT],
            internalName = record[INTERNAL_NAME]!!,
            modifiedTme = modifiedTimeField?.let { record[it] },
            organizationId = record[projects.ORGANIZATION_ID]!!,
            organizationName = record[projects.organizations.NAME]!!,
            projectId = record[PROJECT_ID]!!,
            projectName = record[projects.NAME]!!,
            status = record[APPLICATION_STATUS_ID]!!,
        )
      }
    }
  }
}
