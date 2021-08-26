package com.terraformation.backend.db

import com.fasterxml.jackson.databind.module.SimpleModule
import javax.annotation.ManagedBean
import net.postgis.jdbc.geometry.Geometry

/** Serializer module to parse and render PostGIS [Geometry] objects as GeoJSON. */
@ManagedBean
class GeometryModule : SimpleModule("GeometryModule") {
  init {
    addDeserializer(Geometry::class.java, GeometryDeserializer())

    addSerializer(GeometryCollectionSerializer())
    addSerializer(LineStringSerializer())
    addSerializer(MultiLineStringSerializer())
    addSerializer(MultiPointSerializer())
    addSerializer(MultiPolygonSerializer())
    addSerializer(PointSerializer())
    addSerializer(PolygonSerializer())
  }
}
