package com.terraformation.backend.gis.model

import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.ShapeType
import java.time.Instant

data class FeatureModel(
    val id: FeatureId? = null,
    val layerId: LayerId,
    val shapeType: ShapeType,
    val altitude: Double? = null,
    val gpsHorizAccuracy: Double? = null,
    val gpsVertAccuracy: Double? = null,
    val attrib: String? = null,
    val notes: String? = null,
    val enteredTime: Instant? = null,
    val createdTime: Instant? = null,
    val modifiedTime: Instant? = null,
)
