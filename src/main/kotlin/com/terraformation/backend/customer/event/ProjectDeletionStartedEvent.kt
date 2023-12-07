package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.ProjectId

/**
 * Published when we start deleting all the data related to a project, but before the project has
 * actually been deleted from the database.
 */
data class ProjectDeletionStartedEvent(val projectId: ProjectId)
