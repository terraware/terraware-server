package com.terraformation.backend.db

import net.postgis.jdbc.geometry.Point

/** Returns a PostGIS [Point] with a specific spatial reference ID. */
fun newPoint(x: Double, y: Double, z: Double, srid: Int): Point {
  val point = Point(x, y, z)
  point.srid = srid
  return point
}

/**
 * Returns a [Point] with a given set of spherical Mercator coordinates. This doesn't convert its
 * parameters to spherical Mercator, just annotates the [Point] to indicate that the supplied
 * coordinates are already in that coordinate system.
 */
fun mercatorPoint(x: Double, y: Double, z: Double) = newPoint(x, y, z, SRID.SPHERICAL_MERCATOR)
