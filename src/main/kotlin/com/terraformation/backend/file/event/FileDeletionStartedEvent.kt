package com.terraformation.backend.file.event

import com.terraformation.backend.db.default_schema.FileId

/**
 * Published when a file is about to be deleted. The file will still exist at the time the event is
 * published.
 */
data class FileDeletionStartedEvent(val fileId: FileId, val contentType: String)
