package com.terraformation.backend.file.event

import com.terraformation.backend.db.default_schema.FileId

/**
 * Published when a video file is deleted from the system. The file ID will still be valid when this
 * is published.
 */
data class VideoFileDeletedEvent(val fileId: FileId)
