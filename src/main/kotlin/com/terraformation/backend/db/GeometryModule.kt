package com.terraformation.backend.db

import com.fasterxml.jackson.databind.module.SimpleModule
import jakarta.inject.Named
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.MultiLineString
import org.locationtech.jts.geom.MultiPoint
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon

/** Serializer module to parse and render JTS [Geometry] objects as GeoJSON. */
@Named
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

    addSerializer(GeometrySerializer())
  }
}
