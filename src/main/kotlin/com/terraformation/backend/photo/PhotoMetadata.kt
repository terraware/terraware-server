package com.terraformation.backend.photo

import java.math.BigDecimal
import java.time.Instant

data class PhotoMetadata(
    val filename: String,
    val contentType: String,
    val capturedTime: Instant,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    val gpsAccuracy: Int?
)
