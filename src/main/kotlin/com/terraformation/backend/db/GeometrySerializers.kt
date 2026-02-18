package com.terraformation.backend.db

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.io.geojson.GeoJsonWriter

/**
 * Base class for serializing JTS Geometry objects into GeoJSON. This is just a wrapper around JTS's
 * own GeoJSON writer.
 */
class GeometrySerializer : JsonSerializer<Geometry>() {
  companion object {
    /**
     * Number of digits after the decimal point to render in coordinates.
     *
     * We canonicalize most geometry values to WGS84 coordinates in the database, but the client can
     * choose to send them to us in a different coordinate system. There can be inaccuracies
     * introduced by the conversion: a client could store a longitude of 1 in some other coordinate
     * system and then when we read it back from the database in long/lat form and convert it to the
     * client's coordinate system, it'll be 0.9999999999997.
     *
     * We can't eliminate floating-point inaccuracy in coordinate system conversion, but we can at
     * least mask it by rounding the numbers to a fine-grained enough scale that they won't lose any
     * relevant detail but will still give back whole numbers if the client sent us whole numbers.
     *
     * 8 decimal places in a WGS84 longitude/latitude pair is a precision of 1.1mm, so this is
     * unlikely to introduce any inaccuracy that matters for our application.
     */
    const val COORDINATE_VALUE_SCALE = 8
  }

  /**
   * GeoJSON writer that includes information about what coordinate reference system the coordinates
   * use.
   */
  private val geoJsonWriter = GeoJsonWriter(COORDINATE_VALUE_SCALE)

  /**
   * GeoJSON writer that omits the coordinate reference system child object. We use this when
   * writing geometries that use longitude/latitude coordinates, since that's defined as the default
   * coordinate system for GeoJSON.
   */
  private val noCrsGeoJsonWriter =
      GeoJsonWriter(COORDINATE_VALUE_SCALE).apply { setEncodeCRS(false) }

  override fun serialize(value: Geometry?, gen: JsonGenerator, serializers: SerializerProvider?) {
    if (value != null) {
      val writer = if (value.srid == SRID.LONG_LAT) noCrsGeoJsonWriter else geoJsonWriter
      gen.writeRawValue(writer.write(value))
    } else {
      gen.writeNull()
    }
  }

  override fun handledType(): Class<Geometry> {
    return Geometry::class.java
  }
}
