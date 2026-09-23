package com.terraformation.backend.tracking.model

import com.terraformation.backend.gis.GeometryFileFormat
import java.math.BigDecimal
import org.locationtech.jts.geom.Geometry

data class BoundaryFileModel(
    val areaHa: BigDecimal,
    val filename: String,
    val format: GeometryFileFormat,
    val geometry: Geometry,
    val numPolygons: Int,
)
