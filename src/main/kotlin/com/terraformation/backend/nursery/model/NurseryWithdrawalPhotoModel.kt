package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.default_schema.FileId
import java.time.LocalDateTime
import org.locationtech.jts.geom.Point

data class NurseryWithdrawalPhotoModel(
    val capturedTime: LocalDateTime?,
    val fileId: FileId,
    val gpsCoordinates: Point?,
)
