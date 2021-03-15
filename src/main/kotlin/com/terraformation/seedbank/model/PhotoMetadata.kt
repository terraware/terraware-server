package com.terraformation.seedbank.photo

import java.math.BigDecimal
import java.time.Instant

interface PhotoMetadataFields {
  val filename: String
  val contentType: String
  val capturedTime: Instant
  val latitude: BigDecimal?
  val longitude: BigDecimal?
  val gpsAccuracy: Int?
}

data class PhotoMetadata(
    override val filename: String,
    override val contentType: String,
    override val capturedTime: Instant,
    override val latitude: BigDecimal?,
    override val longitude: BigDecimal?,
    override val gpsAccuracy: Int?
) : PhotoMetadataFields
