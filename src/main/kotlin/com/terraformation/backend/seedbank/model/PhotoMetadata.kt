package com.terraformation.backend.seedbank.model

import java.time.Instant
import net.postgis.jdbc.geometry.Point

data class PhotoMetadata(
    val filename: String,
    val contentType: String,
    val capturedTime: Instant,
    val size: Long,
    val location: Point?,
    val gpsAccuracy: Int?,
)
