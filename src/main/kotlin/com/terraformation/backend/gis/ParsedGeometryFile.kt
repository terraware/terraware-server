package com.terraformation.backend.gis

import org.locationtech.jts.geom.Geometry

data class ParsedGeometryFile(val geometry: Geometry, val format: GeometryFileFormat)

/** Individual shapes in WGS 84 coordinates, before union or topology validation. */
data class ParsedGeometryShapes(val geometries: List<Geometry>, val format: GeometryFileFormat)
