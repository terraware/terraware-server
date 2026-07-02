package com.terraformation.backend.file.event

import com.terraformation.backend.db.default_schema.FileBatchId

data class FileBatchFinishedUploadingEvent(val fileBatchId: FileBatchId)
