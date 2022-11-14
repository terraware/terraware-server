package com.terraformation.backend.file.model

data class PhotoMetadata(
    val filename: String,
    val contentType: String,
    val size: Long,
)
