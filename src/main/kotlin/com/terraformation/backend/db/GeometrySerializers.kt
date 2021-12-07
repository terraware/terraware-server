package com.terraformation.backend.db

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import java.math.RoundingMode
import net.postgis.jdbc.geometry.Geometry
import net.postgis.jdbc.geometry.GeometryCollection
import net.postgis.jdbc.geometry.LineString
import net.postgis.jdbc.geometry.MultiLineString
import net.postgis.jdbc.geometry.MultiPoint
import net.postgis.jdbc.geometry.MultiPolygon
import net.postgis.jdbc.geometry.Point
import net.postgis.jdbc.geometry.Polygon

/**
 * Base class for serializing PostGIS Geometry objects into GeoJSON. Handles writing the GeoJSON
 * fields that are the same across different geometry types.
 */
abstract class BaseGeometrySerializer<T : Geometry>(
    private val handledType: Class<T>,
    private val type: String = handledType.simpleName
) : JsonSerializer<T>() {
  companion object {
    /**
     * Number of digits after the decimal point to render in coordinates.
     *
     * We canonicalize most geometry values to spherical Mercator coordinates in the database, but
     * the client can choose to send them to us in a different coordinate system. There can be
     * inaccuracies introduced by the conversion: a client could store a longitude of 1 and then
     * when we read it back from the database in long/lat form, it'll be 0.9999999999997.
     *
     * We can't eliminate floating-point inaccuracy in coordinate system conversion, but we can at
     * least mask it by rounding the numbers to a fine-grained enough scale that they won't lose any
     * relevant detail but will still give back whole numbers if the client sent us whole numbers.
     */
    const val COORDINATE_VALUE_SCALE = 8
  }

  override fun serialize(value: T?, gen: JsonGenerator, serializers: SerializerProvider?) {
    with(gen) {
      if (value != null) {
        writeStartObject()
        writeStringField("type", type)

        if (value.srid != 0 && value.srid != SRID.LONG_LAT) {
          // "crs": { "type": "name", "properties": {"name": "EPSG:123456"}}
          writeObjectFieldStart("crs")
          writeStringField("type", "name")
          writeObjectFieldStart("properties")
          writeStringField("name", "EPSG:${value.srid}")
          writeEndObject()
          writeEndObject()
        }

        writeGeometryField(value, gen)

        writeEndObject()
      } else {
        writeNull()
      }
    }
  }

  override fun handledType(): Class<T> {
    return handledType
  }

  /**
   * Writes the field that holds the data for a specific geometry type. Subclasses can assume that
   * an object has already been started in [gen] (that is, they can call `writeObjectField`).
   */
  protected abstract fun writeGeometryField(value: T, gen: JsonGenerator)

  /** Writes an array field called "coordinates" with caller-generated contents. */
  protected fun JsonGenerator.writeCoordinates(func: () -> Unit) {
    writeArrayFieldStart("coordinates")
    func()
    writeEndArray()
  }

  /** Writes a sequence of arrays of points. */
  protected fun JsonGenerator.writePoints(points: Array<Point>) {
    points.forEach { point ->
      writeStartArray()
      writeCompactNumber(point.x)
      writeCompactNumber(point.y)
      writeCompactNumber(point.z)
      writeEndArray()
    }
  }

  protected fun JsonGenerator.writePolygonRings(polygon: Polygon) {
    0.until(polygon.numRings()).map { polygon.getRing(it) }.forEach { ring ->
      writeStartArray()
      writePoints(ring.points)
      writeEndArray()
    }
  }

  /**
   * Writes a number without a trailing ".0" if it's a whole value. While the trailing ".0" is valid
   * and correct, this is how PostGIS renders numeric values, and we want to be as close to
   * PostGIS's JSON format as possible.
   */
  protected fun JsonGenerator.writeCompactNumber(number: Double) {
    writeRawValue(
        number
            .toBigDecimal()
            .setScale(COORDINATE_VALUE_SCALE, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString())
  }
}

class GeometryCollectionSerializer :
    BaseGeometrySerializer<GeometryCollection>(GeometryCollection::class.java) {
  override fun writeGeometryField(value: GeometryCollection, gen: JsonGenerator) {
    with(gen) {
      writeArrayFieldStart("geometries")
      value.geometries.forEach { writeObject(it) }
      writeEndArray()
    }
  }
}

class LineStringSerializer : BaseGeometrySerializer<LineString>(LineString::class.java) {
  override fun writeGeometryField(value: LineString, gen: JsonGenerator) {
    with(gen) { writeCoordinates { writePoints(value.points) } }
  }
}

class MultiLineStringSerializer :
    BaseGeometrySerializer<MultiLineString>(MultiLineString::class.java) {
  override fun writeGeometryField(value: MultiLineString, gen: JsonGenerator) {
    with(gen) {
      writeCoordinates {
        value.lines.forEach { line ->
          writeStartArray()
          writePoints(line.points)
          writeEndArray()
        }
      }
    }
  }
}

class MultiPointSerializer : BaseGeometrySerializer<MultiPoint>(MultiPoint::class.java) {
  override fun writeGeometryField(value: MultiPoint, gen: JsonGenerator) {
    with(gen) { writeCoordinates { writePoints(value.points) } }
  }
}

class MultiPolygonSerializer : BaseGeometrySerializer<MultiPolygon>(MultiPolygon::class.java) {
  override fun writeGeometryField(value: MultiPolygon, gen: JsonGenerator) {
    with(gen) {
      writeCoordinates {
        value.polygons.forEach { polygon ->
          writeStartArray()
          writePolygonRings(polygon)
          writeEndArray()
        }
      }
    }
  }
}

class PointSerializer : BaseGeometrySerializer<Point>(Point::class.java) {
  override fun writeGeometryField(value: Point, gen: JsonGenerator) {
    with(gen) {
      writeCoordinates {
        writeCompactNumber(value.x)
        writeCompactNumber(value.y)
        writeCompactNumber(value.z)
      }
    }
  }
}

class PolygonSerializer : BaseGeometrySerializer<Polygon>(Polygon::class.java) {
  override fun writeGeometryField(value: Polygon, gen: JsonGenerator) {
    with(gen) { writeCoordinates { writePolygonRings(value) } }
  }
}
