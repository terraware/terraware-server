package com.terraformation.backend.util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.db.SRID
import com.terraformation.backend.gis.GeometryFileParser
import com.terraformation.backend.log.perClassLogger
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Geometry

class GeometrySimplifierTest {
  private val simplifier = GeometrySimplifier()
  private val objectMapper = jacksonObjectMapper().registerModule(GeometryModule())
  private val parser = GeometryFileParser(objectMapper)

  private val log = perClassLogger()

  @Test
  fun `simplifies geometry`() {
    val original = parseGeoJson("/gis/simplification.geojson")
    val simplified = simplifier.simplify(original)

    val jaccardSimilarity = jaccard(simplified, original)
    val reductionRate = reductionRatio(simplified, original)

    log.info("Jaccard similarity: $jaccardSimilarity")
    log.info("Reduction rate: $reductionRate")

    assertTrue(jaccardSimilarity > 0.95, "Simplified geometry should be very similar")
    assertTrue(reductionRate < 0.70, "Simplified geometry should be smaller")
  }

  /** computes the [Jaccard similarity](https://en.wikipedia.org/wiki/Jaccard_index) */
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
