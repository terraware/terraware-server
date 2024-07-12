package com.terraformation.backend.gis

import com.terraformation.backend.point
import com.terraformation.backend.util.Turtle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CountryDetectorTest {
  private val detector = CountryDetector()

  @Test
  fun `detects a geometry that is in one country`() {
    val geometry = Turtle(point(0, 51)).makePolygon { rectangle(100, 100) }

    assertEquals(setOf("GB"), detector.getCountries(geometry))
  }

  @Test
  fun `detects a geometry that includes multiple countries`() {
    val geometry = Turtle(point(3.5, 50)).makePolygon { rectangle(150000, 100000) }

    assertEquals(setOf("BE", "FR"), detector.getCountries(geometry))
  }

  @Test
  fun `detects a geometry that is completely outside any country`() {
    val geometry = Turtle(point(0, 0)).makePolygon { rectangle(10, 10) }

    assertEquals(emptySet<String>(), detector.getCountries(geometry))
  }
}
