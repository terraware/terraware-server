package com.terraformation.backend.db

import com.fasterxml.jackson.databind.module.SimpleModule
import javax.annotation.ManagedBean
import net.postgis.jdbc.geometry.Geometry
import net.postgis.jdbc.geometry.GeometryCollection
import net.postgis.jdbc.geometry.LineString
import net.postgis.jdbc.geometry.MultiLineString
import net.postgis.jdbc.geometry.MultiPoint
import net.postgis.jdbc.geometry.MultiPolygon
import net.postgis.jdbc.geometry.Point
import net.postgis.jdbc.geometry.Polygon

/** Serializer module to parse and render PostGIS [Geometry] objects as GeoJSON. */
@ManagedBean
class GeometryModule : SimpleModule("GeometryModule") {
  init {
    val deserializer = GeometryDeserializer()
    addDeserializer(Geometry::class.java, deserializer)

    addDeserializer(GeometryCollection::class.java, deserializer.forSubclass())
    addDeserializer(LineString::class.java, deserializer.forSubclass())
    addDeserializer(MultiLineString::class.java, deserializer.forSubclass())
    addDeserializer(MultiPoint::class.java, deserializer.forSubclass())
    addDeserializer(MultiPolygon::class.java, deserializer.forSubclass())
    addDeserializer(Point::class.java, deserializer.forSubclass())
    addDeserializer(Polygon::class.java, deserializer.forSubclass())

    addSerializer(GeometryCollectionSerializer())
    addSerializer(LineStringSerializer())
    addSerializer(MultiLineStringSerializer())
    addSerializer(MultiPointSerializer())
    addSerializer(MultiPolygonSerializer())
    addSerializer(PointSerializer())
    addSerializer(PolygonSerializer())
  }
}
