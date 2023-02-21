package com.terraformation.backend.file.model

data class FileMetadata(
    val filename: String,
    val contentType: String,
    val size: Long,
)
