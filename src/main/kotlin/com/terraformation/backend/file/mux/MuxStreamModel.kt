package com.terraformation.backend.file.mux

import com.terraformation.backend.db.default_schema.FileId

data class MuxStreamModel(
    val fileId: FileId,
    val playbackId: String,
    val playbackToken: String,
)
