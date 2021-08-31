package com.terraformation.backend.gis.model

import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.LayerId
import java.time.Instant
import net.postgis.jdbc.geometry.Geometry

data class FeatureModel(
    val id: FeatureId? = null,
    val layerId: LayerId,
    val geom: Geometry? = null,
    val gpsHorizAccuracy: Double? = null,
    val gpsVertAccuracy: Double? = null,
    val attrib: String? = null,
    val notes: String? = null,
    val enteredTime: Instant? = null,
    val createdTime: Instant? = null,
    val modifiedTime: Instant? = null,
)
