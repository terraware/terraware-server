package com.terraformation.backend.accelerator.event

import com.terraformation.backend.db.default_schema.ProjectId

/** Event for starting a cleanup job for project Google Drive. */
data class ProjectGoogleDriveCleanupEvent(
    val projectId: ProjectId,
    val fileNaming: String,
)
