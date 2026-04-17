package com.terraformation.backend.util

import com.terraformation.backend.db.SRID
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.locationtech.jts.geom.Geometry

import org.locationtech.jts.simplify.TopologyPreservingSimplifier

/**
 * Compresses geometry by simplifying complex lines and combining vertices in a line, using the
 * [Ramer–Douglas–Peucker](https://en.wikipedia.org/wiki/Ramer%E2%80%93Douglas%E2%80%93Peucker_algorithm)
 * method.
 */
class GeometrySimplifier {
  val TOLERANCE_M: Double = 0.5

  /**
   * Simplifies geometry within a tolerance of 0.5 meter. The simplification will be performed in
   * the Web Mercator CRS. The results will be projected back to the original CRS
   */
  fun simplify(geometry: Geometry): Geometry {
    val geometryMercator = project(geometry, geometry.srid, SRID.SPHERICAL_MERCATOR)
    val simplifiedGeometry = TopologyPreservingSimplifier.simplify(geometryMercator, TOLERANCE_M)
    return project(simplifiedGeometry, SRID.SPHERICAL_MERCATOR, geometry.srid)
  }

  private fun project(geometry: Geometry, originalSrid: Int, targetSrid: Int): Geometry {
    val crs = CRS.decode("EPSG:${originalSrid}", true)
    val targetCrs = CRS.decode("EPSG:${targetSrid}", true)

    if (crs == targetCrs) {
      return geometry
    } else {
      val transform = CRS.findMathTransform(crs, targetCrs)
      return JTS.transform(geometry, transform)
    }
  }
}
