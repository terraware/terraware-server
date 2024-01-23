package com.terraformation.backend

import com.terraformation.backend.db.SRID
import org.geotools.api.referencing.crs.CoordinateReferenceSystem
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.geotools.referencing.GeodeticCalculator
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

/**
 * Constructs polygons using a sequence of movements. The movement distances are always in meters.
 *
 * The term "turtle" is from [turtle graphics](https://en.wikipedia.org/wiki/Turtle_graphics).
 */
class Turtle(
    start: Point,
    private val crs: CoordinateReferenceSystem = CRS.decode("EPSG:${SRID.LONG_LAT}", true),
) {
  private val calculator = GeodeticCalculator(crs)
  private val coordinates = mutableListOf<Coordinate>()
  private val geometryFactory = GeometryFactory(PrecisionModel(), start.srid)

  init {
    moveTo(start)
  }

  fun toPolygon(): Polygon {
    return geometryFactory.createPolygon(coordinates.toTypedArray())
  }

  fun toMultiPolygon(): MultiPolygon {
    return geometryFactory.createMultiPolygon(arrayOf(toPolygon()))
  }

  fun north(meters: Number) {
    move(AZIMUTH_NORTH, meters)
  }

  fun east(meters: Number) {
    move(AZIMUTH_EAST, meters)
  }

  fun south(meters: Number) {
    move(AZIMUTH_SOUTH, meters)
  }

  fun west(meters: Number) {
    move(AZIMUTH_WEST, meters)
  }

  fun moveTo(point: Point) {
    coordinates.add(point.coordinate)
    calculator.startingPosition = JTS.toDirectPosition(point.coordinate, crs)
  }

  private fun move(azimuth: Double, meters: Number) {
    calculator.setDirection(azimuth, meters.toDouble())
    coordinates.add(
        Coordinate(
            calculator.destinationPosition.getOrdinate(0),
            calculator.destinationPosition.getOrdinate(1)))
    calculator.startingPosition = calculator.destinationPosition
  }

  companion object {
    private const val AZIMUTH_NORTH: Double = 0.0
    private const val AZIMUTH_EAST: Double = 90.0
    private const val AZIMUTH_SOUTH: Double = 180.0
    private const val AZIMUTH_WEST: Double = 270.0

    /**
     * Returns the polygon formed by a series of turtle moves from a starting point. Automatically
     * closes the polygon; you don't need to move back to the starting point explicitly.
     */
    fun makePolygon(
        start: Point,
        crs: CoordinateReferenceSystem = CRS.decode("EPSG:${SRID.LONG_LAT}", true),
        func: Turtle.() -> Unit
    ): Polygon {
      val turtle = Turtle(start, crs)
      turtle.func()

      // Close the polygon.
      turtle.moveTo(start)

      return turtle.toPolygon()
    }

    /**
     * Returns a one-polygon multipolygon formed by a series of turtle moves from a starting point.
     * Automatically closes the polygon; you don't need to move back to the starting point
     * explicitly.
     */
    fun makeMultiPolygon(
        start: Point,
        crs: CoordinateReferenceSystem = CRS.decode("EPSG:${SRID.LONG_LAT}", true),
        func: Turtle.() -> Unit
    ): MultiPolygon {
      val turtle = Turtle(start, crs)
      turtle.func()

      // Close the polygon.
      turtle.moveTo(start)

      return turtle.toMultiPolygon()
    }
  }
}
