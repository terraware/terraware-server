package com.terraformation.backend.db

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel

/** Returns a JTS [Point] with a specific spatial reference ID. */
fun newPoint(x: Double, y: Double, z: Double, srid: Int): Point {
  return GeometryFactory(PrecisionModel(PrecisionModel.FLOATING), srid)
      .createPoint(Coordinate(x, y, z))
}

/**
 * Returns a [Point] with a given set of spherical Mercator coordinates. This doesn't convert its
 * parameters to spherical Mercator, just annotates the [Point] to indicate that the supplied
 * coordinates are already in that coordinate system.
 */
fun mercatorPoint(x: Double, y: Double, z: Double) = newPoint(x, y, z, SRID.SPHERICAL_MERCATOR)
