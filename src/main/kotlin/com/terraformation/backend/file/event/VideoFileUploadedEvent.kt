package com.terraformation.backend.file.event

import com.terraformation.backend.db.default_schema.FileId

/** Published when a video file is uploaded to the system. */
data class VideoFileUploadedEvent(val fileId: FileId)
