package com.terraformation.backend.util

import com.terraformation.backend.db.SRID
import org.geotools.api.geometry.Position
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
  private var startPosition = JTS.toDirectPosition(start.coordinate, crs)
  private val calculator = GeodeticCalculator(crs)
  private val coordinates = mutableListOf(getCoordinate(startPosition))
  private val geometryFactory = GeometryFactory(PrecisionModel(), start.srid)

  init {
    calculator.startingPosition = startPosition
  }

  /** Clears the accumulated polygon points and starts over at the current turtle position. */
  fun clear() {
    startPosition = calculator.startingPosition
    coordinates.clear()
    coordinates.add(getCoordinate(startPosition))
  }

  fun toPolygon(): Polygon {
    moveTo(startPosition)
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

  /**
   * Traces the south, east, and north sides of a rectangle whose southwest corner is the turtle's
   * current position. Leaves the turtle at the northeast corner such that [toPolygon] will close
   * the rectangle.
   */
  fun rectangle(widthMeters: Number, heightMeters: Number) {
    east(widthMeters)
    north(heightMeters)
    west(widthMeters)
  }

  /**
   * Traces the south, east, and north sides of a square whose southwest corner is the turtle's
   * current position. Leaves the turtle at the northeast corner such that [toPolygon] will close
   * the square.
   */
  fun square(meters: Number) {
    rectangle(meters, meters)
  }

  fun moveStartingPoint(func: Turtle.() -> Unit) {
    this.func()
    clear()
  }

  fun moveTo(point: Point) {
    moveTo(JTS.toDirectPosition(point.coordinate, crs))
  }

  private fun moveTo(position: Position) {
    coordinates.add(getCoordinate(position))
    calculator.startingPosition = position
  }

  private fun getCoordinate(position: Position = calculator.destinationPosition): Coordinate {
    return Coordinate(position.getOrdinate(0), position.getOrdinate(1))
  }

  private fun move(azimuth: Double, meters: Number) {
    calculator.setDirection(azimuth, meters.toDouble())
    coordinates.add(getCoordinate())
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

      return turtle.toMultiPolygon()
    }
  }
}
