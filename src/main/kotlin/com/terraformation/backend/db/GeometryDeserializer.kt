package com.terraformation.backend.db

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import net.postgis.jdbc.geometry.Geometry
import net.postgis.jdbc.geometry.GeometryCollection
import net.postgis.jdbc.geometry.LineString
import net.postgis.jdbc.geometry.LinearRing
import net.postgis.jdbc.geometry.MultiLineString
import net.postgis.jdbc.geometry.MultiPoint
import net.postgis.jdbc.geometry.MultiPolygon
import net.postgis.jdbc.geometry.Point
import net.postgis.jdbc.geometry.Polygon

/** Deserializes GeoJSON strings into PostGIS [Geometry] objects of the appropriate types. */
class GeometryDeserializer : JsonDeserializer<Geometry>() {
  override fun deserialize(jp: JsonParser, ctxt: DeserializationContext): Geometry {
    return readGeometry(jp, jp.readValueAsTree(), null)
  }

  private fun readGeometry(jp: JsonParser, tree: JsonNode, expectedType: String?): Geometry {
    val type =
        tree.findValuesAsText("type").getOrNull(0)
            ?: throw JsonParseException(jp, "Missing geometry type", jp.currentLocation)

    if (expectedType != null && expectedType != type) {
      throw JsonParseException(jp, "Expected type $expectedType, was $type")
    }

    val value =
        if (type == "GeometryCollection") {
          val geometries =
              tree.get("geometries")
                  ?: throw JsonParseException(jp, "Missing geometries", jp.currentLocation)
          readGeometryCollection(jp, geometries)
        } else {
          val coordinates =
              tree.findValue("coordinates")
                  ?: throw JsonParseException(jp, "Missing coordinates", jp.currentLocation)

          when (type) {
            "LineString" -> readLineString(jp, coordinates)
            "MultiLineString" -> MultiLineString(readLineStrings(jp, coordinates))
            "MultiPoint" -> MultiPoint(readPoints(jp, coordinates))
            "MultiPolygon" -> MultiPolygon(readPolygons(jp, coordinates))
            "Point" -> readPoint(jp, coordinates)
            "Polygon" -> Polygon(readLinearRings(jp, coordinates))
            else -> throw JsonParseException(jp, "Unknown geometry type $type", jp.currentLocation)
          }
        }

    val crs = tree.findValue("crs")
    if (crs != null) {
      value.srid = readSrid(jp, crs)
    } else {
      // Use the GeoJSON default SRID.
      value.srid = SRID.LONG_LAT
    }

    return value
  }

  private fun readLineString(jp: JsonParser, tree: JsonNode): LineString {
    val points = readPoints(jp, tree)
    if (points.size < 2) {
      throw JsonParseException(jp, "Line must have at least 2 points", jp.currentLocation)
    }

    return LineString(points)
  }

  private fun readPoint(jp: JsonParser, tree: JsonNode): Point {
    if (!tree.isArray) {
      throw JsonParseException(jp, "Coordinates are not an array", jp.currentLocation)
    }

    val iterator = tree.iterator()
    val x = iterator.next().asDouble()
    val y = iterator.next().asDouble()
    val z = if (iterator.hasNext()) iterator.next().asDouble() else 0.0

    return Point(x, y, z)
  }

  private fun readPoints(jp: JsonParser, tree: JsonNode): Array<Point> {
    if (!tree.isArray) {
      throw JsonParseException(jp, "Point list is not an array", jp.currentLocation)
    }

    return tree.map { readPoint(jp, it) }.toTypedArray()
  }

  private fun readLinearRing(jp: JsonParser, tree: JsonNode): LinearRing {
    val points = readPoints(jp, tree)
    if (points.size < 4) {
      throw JsonParseException(jp, "Polygon must have at least 4 points", jp.currentLocation)
    }
    if (points[0] != points[points.size - 1]) {
      throw JsonParseException(
          jp, "First and last points of polygon must be the same", jp.currentLocation)
    }

    return LinearRing(points)
  }

  private fun readLinearRings(jp: JsonParser, tree: JsonNode): Array<LinearRing> {
    if (!tree.isArray) {
      throw JsonParseException(jp, "List of rings is not an array", jp.currentLocation)
    }

    return tree.map { readLinearRing(jp, it) }.toTypedArray()
  }

  private fun readPolygons(jp: JsonParser, tree: JsonNode): Array<Polygon> {
    if (!tree.isArray) {
      throw JsonParseException(jp, "Polygon list is not an array", jp.currentLocation)
    }

    return tree.map { Polygon(readLinearRings(jp, it)) }.toTypedArray()
  }

  private fun readLineStrings(jp: JsonParser, tree: JsonNode): Array<LineString> {
    if (!tree.isArray) {
      throw JsonParseException(jp, "LineString list is not an array", jp.currentLocation)
    }

    return tree.map { readLineString(jp, it) }.toTypedArray()
  }

  private fun readGeometryCollection(jp: JsonParser, tree: JsonNode): GeometryCollection {
    if (!tree.isArray) {
      throw JsonParseException(jp, "Geometries list is not an array", jp.currentLocation)
    }

    return GeometryCollection(tree.map { readGeometry(jp, it, null) }.toTypedArray())
  }

  private fun readSrid(jp: JsonParser, tree: JsonNode): Int {
    val crsName =
        tree.findValue("properties").findValuesAsText("name").getOrNull(0)
            ?: throw JsonParseException(jp, "No CRS name found", jp.currentLocation)
    if (!crsName.startsWith("EPSG:")) {
      throw JsonParseException(jp, "Unrecognized CRS name format", jp.currentLocation)
    }

    return crsName.substringAfter(':').toIntOrNull()
        ?: throw JsonParseException(jp, "SRID must be a number", jp.currentLocation)
  }

  inner class SubclassDeserializer<T : Geometry>(private val subclass: Class<T>) :
      JsonDeserializer<T>() {
    private val expectedType = subclass.simpleName

    override fun deserialize(jp: JsonParser, ctxt: DeserializationContext): T {
      val geom = readGeometry(jp, jp.readValueAsTree(), expectedType)

      if (subclass.isInstance(geom)) {
        @Suppress("UNCHECKED_CAST") return geom as T
      } else {
        throw JsonParseException(
            jp,
            "Expected geometry type ${subclass.simpleName}, was ${geom.typeString}",
            jp.currentLocation)
      }
    }
  }

  /** Returns a deserializer for a specific geometry type. */
  inline fun <reified T : Geometry> forSubclass(
      subclass: Class<T> = T::class.java
  ): JsonDeserializer<T> {
    return SubclassDeserializer(subclass)
  }
}
