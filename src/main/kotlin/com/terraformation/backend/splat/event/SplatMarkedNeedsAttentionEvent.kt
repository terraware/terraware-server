package com.terraformation.backend.splat.event

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId

/** Published when a user marks an organization splat as needing attention. */
data class SplatMarkedNeedsAttentionEvent(
    val fileId: FileId,
    val organizationId: OrganizationId,
)
