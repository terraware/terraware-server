package com.terraformation.backend.util

import com.terraformation.backend.db.SRID
import java.awt.geom.Point2D
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
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
    moveHorizontally(meters, true)
  }

  fun south(meters: Number) {
    move(AZIMUTH_SOUTH, meters)
  }

  fun west(meters: Number) {
    moveHorizontally(meters, false)
  }

  /**
   * Traces the south, east, and north sides of a rectangle whose southwest corner is the turtle's
   * current position. Leaves the turtle at the northwest corner such that [toPolygon] will close
   * the rectangle.
   *
   * The rectangle's south edge will have the specified width, but due to the curvature of the
   * earth, its north edge will be slightly shorter or longer depending on which hemisphere it's in.
   */
  fun rectangle(widthMeters: Number, heightMeters: Number) {
    calculator.setDirection(AZIMUTH_NORTH, heightMeters.toDouble())
    val northwest = calculator.destinationPosition

    east(widthMeters)
    north(heightMeters)
    moveTo(northwest)
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

  private fun moveHorizontally(meters: Number, isEast: Boolean) {
    val degrees = longitudeDegreesPerMeter(calculator.startingGeographicPoint.y) * meters.toDouble()

    val longitudeWithDelta =
        if (isEast) {
          calculator.startingGeographicPoint.x + degrees
        } else {
          calculator.startingGeographicPoint.x - degrees
        }

    // Normalize longitude to a range of -180 to 180 if the movement crosses the antimeridian
    val normalizedLongitude =
        if (longitudeWithDelta >= -180.0 && longitudeWithDelta <= 180.0) {
          longitudeWithDelta
        } else {
          (longitudeWithDelta + 180.0).mod(360.0) - 180.0
        }

    calculator.startingGeographicPoint =
        Point2D.Double(normalizedLongitude, calculator.startingGeographicPoint.y)

    coordinates.add(getCoordinate(calculator.startingPosition))
  }

  /**
   * Returns the number of degrees of longitude per meter at a latitude. This is based on the length
   * of a rhumb line path to the east or west, not the length of a great-circle path.
   *
   * Formula is the inverse of the ellipsoid "length of a degree of longitude" formula from
   * https://en.wikipedia.org/wiki/Longitude.
   */
  private fun longitudeDegreesPerMeter(latitude: Double): Double {
    val radians = Math.toRadians(latitude)

    return (180.0 * sqrt(1.0 - (ELLIPSOID_ECCENTRICITY_SQUARED * sin(radians).pow(2.0)))) /
        (Math.PI * ELLIPSOID_SEMI_MAJOR_METERS * cos(radians))
  }

  companion object {
    private const val AZIMUTH_NORTH: Double = 0.0
    private const val AZIMUTH_SOUTH: Double = 180.0

    // Constants for calculating degrees of longitude per meter on the WGS-84 ellipsoid.
    private const val ELLIPSOID_SEMI_MAJOR_METERS = 6378137.0
    private const val ELLIPSOID_SEMI_MINOR_METERS = 6356752.3142
    private const val ELLIPSOID_ECCENTRICITY_SQUARED =
        (ELLIPSOID_SEMI_MAJOR_METERS * ELLIPSOID_SEMI_MAJOR_METERS -
            ELLIPSOID_SEMI_MINOR_METERS * ELLIPSOID_SEMI_MINOR_METERS) /
            (ELLIPSOID_SEMI_MAJOR_METERS * ELLIPSOID_SEMI_MAJOR_METERS)

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
