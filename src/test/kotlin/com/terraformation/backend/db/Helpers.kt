package com.terraformation.backend.db

import net.postgis.jdbc.geometry.Geometry
import net.postgis.jdbc.geometry.Point
import org.junit.jupiter.api.Assertions.assertEquals

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

/**
 * Assert Point coordinate data is equal. Since the coordinates probably went through at least one
 * coordinate system conversion in the database write/read, allow a small fuzz factor in the x and y
 * coordinates.
 */
fun assertPointsEqual(expected: Geometry, actual: Geometry) {
  assertEquals(expected.type, Geometry.POINT) // this function only compares "Point" types
  assertEquals(expected.type, actual.type)
  assertEquals(expected.srid, actual.srid)
  assertEquals(expected.firstPoint.x, actual.firstPoint.x, 0.0001)
  assertEquals(expected.firstPoint.y, actual.firstPoint.y, 0.0001)
  assertEquals(expected.firstPoint.z, actual.firstPoint.z)
}
