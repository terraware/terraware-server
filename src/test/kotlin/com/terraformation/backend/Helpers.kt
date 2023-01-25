package com.terraformation.backend

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.db.SRID
import org.junit.jupiter.api.Assertions.assertEquals
import org.locationtech.jts.geom.CoordinateXY
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon

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

/** Creates a simple triangular MultiPolygon. */
fun multiPolygon(scale: Double): MultiPolygon {
  val geometryFactory = GeometryFactory()
  return geometryFactory
      .createMultiPolygon(
          arrayOf(
              geometryFactory.createPolygon(
                  arrayOf(
                      CoordinateXY(0.0, 0.0),
                      CoordinateXY(scale, 0.0),
                      CoordinateXY(scale, scale),
                      CoordinateXY(0.0, 0.0)))))
      .also { it.srid = SRID.LONG_LAT }
}
