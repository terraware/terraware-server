package com.terraformation.backend.seedbank.model

data class PhotoMetadata(
    val filename: String,
    val contentType: String,
    val size: Long,
)
