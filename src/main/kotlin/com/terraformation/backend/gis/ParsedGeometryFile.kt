package com.terraformation.backend.gis

import org.locationtech.jts.geom.Geometry

data class ParsedGeometryFile(val geometry: Geometry, val format: GeometryFileFormat)
