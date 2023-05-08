package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.SRID
import org.geotools.referencing.CRS
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.CoordinateXY
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel

class ShapefileFeatureTest {
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
                          CoordinateXY(373929.1743, 662471.6669)))))
      val feature = ShapefileFeature(geometry, emptyMap(), CRS.decode("EPSG:$srid"))

      assertEquals(101.8, feature.calculateAreaHectares(), 0.05)
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
                          CoordinateXY(-76.13567116641384, 5.989357251936355)))))
      val feature = ShapefileFeature(geometry, emptyMap(), DefaultGeographicCRS.WGS84)

      assertEquals(101.8, feature.calculateAreaHectares(), 0.05)
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
                          CoordinateXY(-8475384.145449309, 667949.7974625841)))))
      val feature =
          ShapefileFeature(geometry, emptyMap(), CRS.decode("EPSG:${SRID.SPHERICAL_MERCATOR}"))

      assertEquals(101.8, feature.calculateAreaHectares(), 0.05)
    }
  }
}
