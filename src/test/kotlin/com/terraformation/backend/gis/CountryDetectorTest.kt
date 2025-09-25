package com.terraformation.backend.gis

import com.terraformation.backend.TestSingletons
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.point
import com.terraformation.backend.util.Turtle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CountryDetectorTest {
  private val detector = TestSingletons.countryDetector

  @Test
  fun `detects a geometry that is in one country`() {
    val geometry = Turtle(point(0, 51)).makePolygon { rectangle(100, 100) }

    assertSetEquals(setOf("GB"), detector.getCountries(geometry))
  }

  @Test
  fun `detects a geometry that includes multiple countries`() {
    val geometry = Turtle(point(3.5, 50)).makePolygon { rectangle(150000, 100000) }

    assertSetEquals(setOf("BE", "FR"), detector.getCountries(geometry))
  }

  @Test
  fun `detects a geometry that is completely outside any country`() {
    val geometry = Turtle(point(0, 0)).makePolygon { rectangle(10, 10) }

    assertSetEquals(emptySet<String>(), detector.getCountries(geometry))
  }

  @Test
  fun `ignores a country that only contains a small percent of a geometry`() {
    val geometry = Turtle(point(3.5, 50)).makePolygon { rectangle(150000, 134000) }
    val netherlandsIntersectionPercent =
        detector.intersectionArea("NL", geometry) / geometry.area * 100.0

    assertThat(netherlandsIntersectionPercent)
        .isGreaterThan(0.0)
        .isLessThan(CountryDetector.MIN_COVERAGE_PERCENT)
        .describedAs("Geometry intersects NL a little bit")

    assertSetEquals(setOf("BE", "FR"), detector.getCountries(geometry))
  }
}
