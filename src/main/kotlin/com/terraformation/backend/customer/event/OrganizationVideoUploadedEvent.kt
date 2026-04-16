package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId

/** Published when a video file is uploaded as organization media. */
data class OrganizationVideoUploadedEvent(
    val fileId: FileId,
    val organizationId: OrganizationId,
)
