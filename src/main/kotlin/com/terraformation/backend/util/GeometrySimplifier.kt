package com.terraformation.backend.util

import com.terraformation.backend.db.SRID
import jakarta.inject.Named
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.simplify.TopologyPreservingSimplifier

/**
 * Compresses geometry by simplifying complex lines and combining vertices in a line, using the
 * [Ramer–Douglas–Peucker](https://en.wikipedia.org/wiki/Ramer%E2%80%93Douglas%E2%80%93Peucker_algorithm)
 * method.
 */
@Named
class GeometrySimplifier {
  private val DEFAULT_TOLERANCE_M: Double = 0.5

  /**
   * Simplifies geometry within a tolerance of 0.5 meter. The simplification will be performed in
   * the Web Mercator CRS. The results will be projected back to the original CRS
   */
  fun simplify(geometry: Geometry, tolerance: Double? = null): Geometry {
    val originalSrid = geometry.srid
    val geometryMercator = geometry.projectTo(SRID.SPHERICAL_MERCATOR)
    val simplifiedGeometry =
        TopologyPreservingSimplifier.simplify(geometryMercator, tolerance ?: DEFAULT_TOLERANCE_M)

    // The simplifier builds its result with the input's GeometryFactory, whose SRID is the one the
    // geometry had before it was projected.
    simplifiedGeometry.srid = SRID.SPHERICAL_MERCATOR

    return simplifiedGeometry.projectTo(originalSrid)
  }
}
