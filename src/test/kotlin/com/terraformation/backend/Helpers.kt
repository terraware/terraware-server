package com.terraformation.backend

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.auth.KeycloakInfo
import com.terraformation.backend.db.SRID
import org.junit.jupiter.api.Assertions.assertEquals
import org.locationtech.jts.geom.CoordinateXY
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

/**
 * ObjectMapper configured to pretty print. This is lazily instantiated since ObjectMappers aren't
 * terribly lightweight.
 */
private val prettyPrintingObjectMapper: ObjectMapper by lazy {
  jacksonObjectMapper()
      .registerModule(JavaTimeModule())
      .enable(SerializationFeature.INDENT_OUTPUT)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
}

/**
 * Asserts that two objects are equal and, if they're not, outputs the comparison failure using
 * pretty-printed JSON rather than the outputs of their `toString` methods.
 *
 * This makes it easier to examine differences between accession objects with lots of field values.
 */
fun assertJsonEquals(expected: Any, actual: Any, message: String? = null) {
  if (expected != actual) {
    assertEquals(
        prettyPrintingObjectMapper.writeValueAsString(expected),
        prettyPrintingObjectMapper.writeValueAsString(actual),
        message)
  }
}

fun point(x: Double, y: Double = x): Point {
  val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
  return geometryFactory.createPoint(CoordinateXY(x, y))
}

/** Creates a rectangular Polygon. */
fun polygon(left: Double, bottom: Double, right: Double, top: Double): Polygon {
  val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
  return geometryFactory.createPolygon(
      arrayOf(
          CoordinateXY(left, bottom),
          CoordinateXY(right, bottom),
          CoordinateXY(right, top),
          CoordinateXY(left, top),
          CoordinateXY(left, bottom)))
}

/** Creates a square Polygon with its left bottom corner at the origin. */
fun polygon(scale: Double): Polygon {
  return polygon(0.0, 0.0, scale, scale)
}

/** Wraps a Polygon in a MultiPolygon. */
fun multiPolygon(polygon: Polygon): MultiPolygon {
  val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
  return geometryFactory.createMultiPolygon(arrayOf(polygon))
}

/** Creates a simple triangular MultiPolygon. */
fun multiPolygon(scale: Double): MultiPolygon {
  return multiPolygon(polygon(scale))
}

/**
 * Returns dummy information about Keycloak. This can be used to test code that generates
 * Keycloak-related output such as registration URLs.
 */
fun dummyKeycloakInfo() = KeycloakInfo("client-id", "secret", "http://dummy/realms/terraware")
