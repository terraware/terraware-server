package com.terraformation.backend

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.auth.KeycloakInfo
import com.terraformation.backend.db.SRID
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.toMultiPolygon
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.locationtech.jts.geom.Coordinate
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

fun point(x: Number, y: Number = x, z: Number? = null, srid: Int = SRID.LONG_LAT): Point {
  val geometryFactory = GeometryFactory(PrecisionModel(), srid)
  return geometryFactory.createPoint(
      Coordinate(x.toDouble(), y.toDouble(), z?.toDouble() ?: Coordinate.NULL_ORDINATE))
}

/** Creates a rectangular Polygon. */
fun polygon(left: Number, bottom: Number, right: Number, top: Number): Polygon {
  val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
  return geometryFactory.createPolygon(
      arrayOf(
          CoordinateXY(left.toDouble(), bottom.toDouble()),
          CoordinateXY(right.toDouble(), bottom.toDouble()),
          CoordinateXY(right.toDouble(), top.toDouble()),
          CoordinateXY(left.toDouble(), top.toDouble()),
          CoordinateXY(left.toDouble(), bottom.toDouble())))
}

/** Creates a square Polygon with its left bottom corner at the origin. */
fun polygon(scale: Number): Polygon {
  return polygon(0.0, 0.0, scale, scale)
}

/** Wraps a Polygon in a MultiPolygon. */
fun multiPolygon(polygon: Polygon): MultiPolygon {
  return polygon.factory.createMultiPolygon(arrayOf(polygon))
}

/** Creates a simple rectangular MultiPolygon. */
fun multiPolygon(scale: Number): MultiPolygon {
  return multiPolygon(polygon(scale))
}

/** Returns a rectangular MultiPolygon with position and size in meters. */
fun rectangle(
    width: Number,
    height: Number = width,
    x: Number = 0,
    y: Number = 0,
): MultiPolygon {
  return if (width == 0) {
    GeometryFactory(PrecisionModel(), SRID.LONG_LAT).createMultiPolygon(emptyArray())
  } else {
    Turtle(point(1))
        .makeMultiPolygon {
          north(y)
          east(x)
          rectangle(width, height)
        }
        .norm()
        .toMultiPolygon()
  }
}

/** Returns a rectangular Polygon with position and size in meters. */
fun rectanglePolygon(
    width: Number,
    height: Number = width,
    x: Number = 0,
    y: Number = 0,
): Polygon =
    Turtle(point(1))
        .makePolygon {
          north(y)
          east(x)
          rectangle(width, height)
        }
        .norm() as Polygon

/**
 * Returns dummy information about Keycloak. This can be used to test code that generates
 * Keycloak-related output such as registration URLs.
 */
fun dummyKeycloakInfo() = KeycloakInfo("client-id", "secret", "http://dummy/realms/terraware")

/** A 1-pixel PNG file for testing code that requires valid image data. */
val onePixelPng: ByteArray by lazy {
  TestClock::class.java.getResourceAsStream("/file/pixel.png").use { it.readAllBytes() }
}

/**
 * Converts an arbitrary numeric type to a BigDecimal. May involve converting it to a Double as an
 * intermediate step.
 *
 * This is analogous to the `toBigDecimal()` extension methods in the Kotlin standard library, but
 * can be called on an unknown numeric type.
 */
fun Number.toBigDecimal(): BigDecimal =
    when (this) {
      is BigDecimal -> this
      is Int -> BigDecimal(this)
      is Long -> BigDecimal(this)
      // Let BigDecimal parse the string representation; this is how the toBigDecimal() extension
      // methods in the Kotlin standard library do it for Float and Double types, and we want to
      // return the same values they do.
      else -> BigDecimal(toString())
    }
