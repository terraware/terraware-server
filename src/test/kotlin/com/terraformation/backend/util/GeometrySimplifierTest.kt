package com.terraformation.backend.util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.db.SRID
import com.terraformation.backend.gis.GeometryFileParser
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel

class GeometrySimplifierTest {
  private val objectMapper = jacksonObjectMapper().registerModule(GeometryModule())
  private val parser = GeometryFileParser(objectMapper)

  @Test
  fun `simplifies geometry for a polygon`() {
    runGeoJson("/gis/simplification/simple.geojson")
  }

  @Test
  fun `simplifies geometry for a multi-polygon`() {
    runGeoJson("/gis/simplification/multipolygon.geojson")
  }

  @Test
  fun `smooths line by removing points according to distance tolerances`() {
    val geometryFactory = GeometryFactory(PrecisionModel(), SRID.SPHERICAL_MERCATOR)
    val originalPolygon =
        geometryFactory.createPolygon(
            arrayOf(
                Coordinate(0.0, 0.0),
                Coordinate(6.0, 0.6), // divot of 0.6m
                Coordinate(12.0, 0.0),
                Coordinate(12.0, 10.0),
                Coordinate(6.0, 11.1), // divot of 1.1m
                Coordinate(0.0, 10.0),
                Coordinate(0.0, 0.0),
            )
        )

    val expectedSimplifiedPolygon2 =
        geometryFactory.createPolygon(
            arrayOf(
                Coordinate(0.0, 0.0),
                // 0.6m divot removed
                Coordinate(12.0, 0.0),
                Coordinate(12.0, 10.0),
                Coordinate(6.0, 11.1), // divot of 1.1m
                Coordinate(0.0, 10.0),
                Coordinate(0.0, 0.0),
            )
        )

    val expectedSimplifiedPolygon3 =
        geometryFactory.createPolygon(
            arrayOf(
                Coordinate(0.0, 0.0),
                // 0.6m divot removed
                Coordinate(12.0, 0.0),
                Coordinate(12.0, 10.0),
                // 1.1m divot removed
                Coordinate(0.0, 10.0),
                Coordinate(0.0, 0.0),
            )
        )

    val actualSimplifiedPolygon1 = GeometrySimplifier.simplify(originalPolygon, 0.5)
    val actualSimplifiedPolygon2 = GeometrySimplifier.simplify(originalPolygon, 1.0)
    val actualSimplifiedPolygon3 = GeometrySimplifier.simplify(originalPolygon, 1.5)

    assertGeometryEquals(
        originalPolygon,
        actualSimplifiedPolygon1,
        "Simplified polygon with tolerance of 0.5m",
    )
    assertGeometryEquals(
        expectedSimplifiedPolygon2,
        actualSimplifiedPolygon2,
        "Simplified polygon with tolerance of 1.0m",
    )
    assertGeometryEquals(
        expectedSimplifiedPolygon3,
        actualSimplifiedPolygon3,
        "Simplified polygon with tolerance of 1.5m",
    )
  }

  private fun runGeoJson(path: String) {
    val original = parseGeoJson(path)

    val simplified = GeometrySimplifier.simplify(original)
    val jaccardSimilarity = jaccard(simplified, original)
    val reductionRate = reductionRatio(simplified, original)

    assertTrue(jaccardSimilarity > 0.95, "Simplified geometry should be very similar")
    assertTrue(reductionRate > 0.50, "Simplified geometry should be smaller")
  }

  private fun jaccard(simplified: Geometry, original: Geometry): Double {
    val projectedOriginal = projectToMercator(original)
    val projectedSimplified = projectToMercator(simplified)
    val intersection = projectedOriginal.intersection(projectedSimplified)
    val union = projectedOriginal.union(projectedSimplified)

    return intersection.area / union.area
  }

  /** computes the reduction ratio */
  private fun reductionRatio(simplified: Geometry, original: Geometry): Double {
    return 1.0 - (simplified.numPoints.toDouble() / original.numPoints)
  }

  private fun projectToMercator(geometry: Geometry): Geometry {
    val crs = CRS.decode("EPSG:${geometry.srid}", true)
    val targetCrs = CRS.decode("EPSG:${SRID.SPHERICAL_MERCATOR}", true)

    if (crs == targetCrs) {
      return geometry
    } else {
      val transform = CRS.findMathTransform(crs, targetCrs)
      return JTS.transform(geometry, transform)
    }
  }

  private fun parseGeoJson(resourcePath: String): Geometry {
    javaClass.getResourceAsStream(resourcePath).use { stream ->
      val bytes = stream.readAllBytes()

      return parser.parse(bytes, resourcePath)
    }
  }
}
