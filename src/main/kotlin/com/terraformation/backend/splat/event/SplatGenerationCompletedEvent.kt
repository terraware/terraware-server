package com.terraformation.backend.splat.event

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant

data class SplatGenerationCompletedEvent(
    val fileId: FileId,
    val organizationId: OrganizationId,
    val uploadedByUserId: UserId,
    val videoUploadedTime: Instant,
)
