package com.terraformation.backend.splat.event

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant

/** Published when a user marks an organization splat as needing attention. */
data class SplatMarkedNeedsAttentionEvent(
    val fileId: FileId,
    val markedByUserId: UserId,
    val organizationId: OrganizationId,
    val uploadedByUserId: UserId,
    val videoUploadedTime: Instant,
)
