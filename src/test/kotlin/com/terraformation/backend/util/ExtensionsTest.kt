package com.terraformation.backend.util

import com.terraformation.backend.db.SRID
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.CoordinateXY
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel

class ExtensionsTest {
  @Test
  fun `toInstant converts date to correct instant`() {
    val date = LocalDate.of(2023, 1, 1)
    val time = LocalTime.of(2, 3, 4, 0)
    val timeZone = ZoneId.of("America/Los_Angeles")

    assertEquals(Instant.ofEpochSecond(1672567384), date.toInstant(timeZone, time))
  }

  @Nested
  inner class CalculateAreaHectares {
    @Test
    fun `calculates correct value for UTM coordinates`() {
      val srid = 21818 // UTM zone 18N
      val factory = GeometryFactory(PrecisionModel(), srid)
      val geometry =
          factory.createMultiPolygon(
              arrayOf(
                  factory.createPolygon(
                      arrayOf(
                          CoordinateXY(373929.1743, 662471.6669),
                          CoordinateXY(374819.6281, 662469.8334),
                          CoordinateXY(374817.2842, 661326.4376),
                          CoordinateXY(373926.8137, 661328.268),
                          CoordinateXY(373929.1743, 662471.6669),
                      )
                  )
              )
          )

      assertEquals(BigDecimal("101.8"), geometry.calculateAreaHectares())
    }

    @Test
    fun `calculates correct value for WGS84 coordinates`() {
      val factory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
      val geometry =
          factory.createMultiPolygon(
              arrayOf(
                  factory.createPolygon(
                      arrayOf(
                          CoordinateXY(-76.13567116641384, 5.989357251936355),
                          CoordinateXY(-76.12762679268639, 5.989357251773201),
                          CoordinateXY(-76.12762679270281, 5.979015683955292),
                          CoordinateXY(-76.13567116598097, 5.979015684419131),
                          CoordinateXY(-76.13567116641384, 5.989357251936355),
                      )
                  )
              )
          )

      assertEquals(BigDecimal("101.8"), geometry.calculateAreaHectares())
    }

    @Test
    fun `calculates correct value for spherical Mercator coordinates`() {
      val factory = GeometryFactory(PrecisionModel(), SRID.SPHERICAL_MERCATOR)
      val geometry =
          factory.createMultiPolygon(
              arrayOf(
                  factory.createPolygon(
                      arrayOf(
                          CoordinateXY(-8475384.145449309, 667949.7974625841),
                          CoordinateXY(-8474488.649862219, 667949.7974443221),
                          CoordinateXY(-8474488.649864046, 666792.2716823813),
                          CoordinateXY(-8475384.145401122, 666792.2717342979),
                          CoordinateXY(-8475384.145449309, 667949.7974625841),
                      )
                  )
              )
          )

      assertEquals(BigDecimal("101.8"), geometry.calculateAreaHectares())
    }
  }

  @Nested
  inner class ToMultiPolygon {
    @Test
    fun `converts GeometryCollection to MultiPolygon even if it includes Points`() {
      val factory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
      val polygon1 =
          factory.createPolygon(
              arrayOf(
                  CoordinateXY(0.0, 0.0),
                  CoordinateXY(0.0, 1.0),
                  CoordinateXY(1.0, 1.0),
                  CoordinateXY(0.0, 0.0),
              )
          )
      val polygon2 =
          factory.createPolygon(
              arrayOf(
                  CoordinateXY(10.0, 0.0),
                  CoordinateXY(10.0, 1.0),
                  CoordinateXY(11.0, 1.0),
                  CoordinateXY(10.0, 0.0),
              )
          )
      val geometryCollection =
          factory.createGeometryCollection(
              arrayOf(polygon1, polygon2, factory.createPoint(CoordinateXY(20.0, 0.0)))
          )

      assertEquals(
          factory.createMultiPolygon(arrayOf(polygon1, polygon2)),
          geometryCollection.toMultiPolygon(),
      )
    }
  }
}
