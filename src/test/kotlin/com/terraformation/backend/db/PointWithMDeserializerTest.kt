package com.terraformation.backend.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Point
import tools.jackson.core.exc.StreamReadException
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue

internal class PointWithMDeserializerTest {
  private val objectMapper = jacksonMapperBuilder().addModule(GeometryModule()).build()

  data class Payload(@JsonDeserialize(using = PointWithMDeserializer::class) val point: Point?)

  @Test
  fun `parses 4D point preserving M coordinate`() {
    val json = """{"point":{"type":"Point","coordinates":[1.0,2.0,3.0,4.0]}}"""
    val point = objectMapper.readValue<Payload>(json).point!!

    assertEquals(1.0, point.x)
    assertEquals(2.0, point.y)
    assertEquals(3.0, point.coordinate.z)
    assertEquals(4.0, point.coordinate.m)
  }

  @Test
  fun `parses 3D point with NaN M`() {
    val json = """{"point":{"type":"Point","coordinates":[1.0,2.0,3.0]}}"""
    val point = objectMapper.readValue<Payload>(json).point!!

    assertEquals(1.0, point.x)
    assertEquals(2.0, point.y)
    assertEquals(3.0, point.coordinate.z)
    assertTrue(point.coordinate.m.isNaN())
  }

  @Test
  fun `rejects non-Point geometry type`() {
    val json = """{"point":{"type":"LineString","coordinates":[[1.0,2.0],[3.0,4.0]]}}"""
    assertThrows<StreamReadException> { objectMapper.readValue<Payload>(json) }
  }

  @Test
  fun `rejects point with fewer than 2 coordinates`() {
    val json = """{"point":{"type":"Point","coordinates":[1.0]}}"""
    assertThrows<StreamReadException> { objectMapper.readValue<Payload>(json) }
  }
}
