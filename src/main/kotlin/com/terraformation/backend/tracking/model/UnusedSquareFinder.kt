package com.terraformation.backend.tracking.model

import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.createRectangle
import com.terraformation.backend.util.fixIfNeeded
import com.terraformation.backend.util.nearlyCoveredBy
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.geotools.referencing.GeodeticCalculator
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon

/** Finds an unused grid-aligned square within a zone boundary. */
class UnusedSquareFinder(
    zoneBoundary: MultiPolygon,
    private val gridOrigin: Point,
    private val sizeMeters: Number,
    /** Areas to exclude from the zone, or null if the whole zone is available. */
    exclusion: MultiPolygon? = null,
) {
  private val geometryFactory = zoneBoundary.factory
  private val boundaryCrs = CRS.decode("EPSG:${zoneBoundary.srid}", true)
  private val calculator = GeodeticCalculator(boundaryCrs)
  private val gridInterval: Double = sizeMeters.toDouble()

  /** The geometry of the zone's available area (minus exclusion and existing plots). */
  private val zoneGeometry =
      zoneBoundary
          .fixIfNeeded()
          .let { if (exclusion != null) it.difference(exclusion.fixIfNeeded()) else it }
          .fixIfNeeded()

  init {
    calculator.startingPosition = JTS.toDirectPosition(gridOrigin.coordinate, boundaryCrs)
  }

  /**
   * Returns a square Polygon of the requested width/height that is completely contained in the zone
   * and does not intersect with an exclusion area.
   *
   * The square will be positioned a whole multiple of [gridInterval] meters from the specified
   * origin.
   *
   * @return The unused square, or null if no suitable area could be found.
   */
  fun findUnusedSquare(): Polygon? {
    // The envelope of the zone geometry may be wider on the north edge than on the south or vice
    // versa because degrees of longitude represent different distances depending on latitude.
    // So we convert all 4 corners of the envelope to meter offsets from the origin, and use the
    // minimum and maximum values to determine the meter-based search region.
    val zoneEnvelope = zoneGeometry.envelope
    val zoneEnvelopeMeters = zoneEnvelope.coordinates.map { getMeterOffsets(it) }

    // Extend the initial search area beyond the actual zone envelope so that we're equally likely
    // to choose an edge location as an interior location.
    //
    // If we don't do this, then the interior grid lines are chosen when a random position is within
    // (sizeMeters / 2) in either direction, whereas an edge location is only chosen when the
    // position is within (sizeMeters / 2) in one direction because the points that would round
    // to it from the other direction would be outside the random number range.
    val margin = sizeMeters.toDouble() / 2.0

    return findInRegion(
        Coordinate(
            zoneEnvelopeMeters.minOf { it.x } - margin,
            zoneEnvelopeMeters.minOf { it.y } - margin,
        ),
        Coordinate(
            zoneEnvelopeMeters.maxOf { it.x } + margin,
            zoneEnvelopeMeters.maxOf { it.y } + margin,
        ),
    )
  }

  private fun roundToGrid(value: Double): Double = Math.round(value / gridInterval) * gridInterval

  /** Returns a rectangle with corners a specified number of meters from the grid origin. */
  private fun meterOffsetRectangle(southwest: Coordinate, northeast: Coordinate): Polygon {
    return Turtle(gridOrigin, boundaryCrs).makePolygon {
      north(southwest.y)
      east(southwest.x)

      rectangle(northeast.x - southwest.x, northeast.y - southwest.y)
    }
  }

  /**
   * Returns a square [sizeMeters] on a side with the coordinates snapped to multiples of
   * [gridInterval] meters from the grid origin.
   */
  private fun gridAlignedSquare(westEdge: Double, southEdge: Double): Polygon {
    return Turtle(gridOrigin, boundaryCrs).makePolygon {
      north(roundToGrid(southEdge))
      east(roundToGrid(westEdge))

      square(sizeMeters)
    }
  }

  /**
   * Returns the distance from the grid origin to a particular set of coordinates as an offset in
   * meters along each axis.
   */
  private fun getMeterOffsets(coordinate: Coordinate): Coordinate {
    calculator.destinationPosition = JTS.toDirectPosition(coordinate, boundaryCrs)

    val azimuthRadians = calculator.azimuth * Math.PI / 180.0
    val distanceMeters = calculator.orthodromicDistance
    val x = sin(azimuthRadians) * distanceMeters
    val y = cos(azimuthRadians) * distanceMeters

    return Coordinate(x, y)
  }

  /**
   * Returns true if a polygon is sufficiently covered by the zone geometry. If an edge of the site
   * geometry is axis-aligned, rounding errors can cause a polygon on the edge to test as not
   * completely covered by the zone. So instead we test that the polygon is at least 99.9% covered.
   */
  private fun coveredByZone(polygon: Geometry): Boolean {
    return polygon.nearlyCoveredBy(zoneGeometry, 99.9)
  }

  /**
   * Attempts to find an available square within a rectangular region of the zone. First tries to
   * pick a few random points, and if none of them works, divides the region into four quadrants and
   * searches them in area-weighted random order until it finds a match.
   *
   * The idea is that for a typical zone, the initial random probe will most likely work and we'll
   * return a result quickly, but for an odd-shaped zone, we keep drilling down until we've done an
   * exhaustive search for matches.
   */
  private fun findInRegion(southwest: Coordinate, northeast: Coordinate): Polygon? {
    val regionWidth = northeast.x - southwest.x
    val regionHeight = northeast.y - southwest.y

    // If the region is less than the size of a grid square in both height and width, check it and
    // return; trying random points within the region wouldn't be useful, nor would dividing the
    // region and drilling down.
    if (regionWidth < gridInterval && regionHeight < gridInterval) {
      val polygon = gridAlignedSquare(southwest.x, southwest.y)

      return if (coveredByZone(polygon)) {
        polygon
      } else {
        null
      }
    }

    // Try a few times to find a random spot in the region. This will succeed most of the time.
    val maxAttempts = 5

    for (attemptNumber in 1..maxAttempts) {
      val polygon =
          gridAlignedSquare(
              Random.nextDouble(southwest.x, northeast.x),
              Random.nextDouble(southwest.y, northeast.y),
          )

      if (coveredByZone(polygon)) {
        return polygon
      }
    }

    // No luck. This might be a sparse site map. Split the region into smaller areas and search them
    // in random order but with the ones that cover the most zone area more likely to come first.
    //
    // The subdivided areas have some overlap; otherwise there can be scenarios where the only
    // available result straddles the border of two subdivided areas and thus can't be found in
    // either of them.

    val xRanges =
        if (regionWidth >= gridInterval) {
          listOf(
              southwest.x to (southwest.x + northeast.x * 2.0) / 3.0,
              (southwest.x + northeast.x) / 2.0 to northeast.x,
          )
        } else {
          listOf(southwest.x to northeast.x)
        }
    val yRanges =
        if (regionHeight >= gridInterval) {
          listOf(
              southwest.y to (southwest.y + northeast.y * 2.0) / 3.0,
              (southwest.y + northeast.y) / 2.0 to northeast.y,
          )
        } else {
          listOf(southwest.y to northeast.y)
        }

    // List of quadrant geometry and how much usable area the quadrant has.
    val quadrants: MutableList<Pair<Polygon, Double>> =
        xRanges
            .flatMap { (west, east) ->
              yRanges.map { (south, north) ->
                geometryFactory.createRectangle(west, south, east, north)
              }
            }
            .map { quadrant ->
              val quadrantPolygon =
                  meterOffsetRectangle(quadrant.coordinates[0], quadrant.coordinates[2])
              quadrant to zoneGeometry.intersection(quadrantPolygon).area
            }
            // Exclude quadrants that don't cover any zone area at all.
            .filter { it.second > 0 }
            .toMutableList()

    while (quadrants.isNotEmpty()) {
      val totalIntersectionArea = quadrants.sumOf { it.second }
      val weightedSelection = Random.nextDouble(totalIntersectionArea)
      var totalVisitedWeight = 0.0

      for (index in quadrants.indices) {
        totalVisitedWeight += quadrants[index].second

        if (totalVisitedWeight >= weightedSelection) {
          val selectedQuadrant = quadrants.removeAt(index).first

          val findResult =
              findInRegion(selectedQuadrant.coordinates[0], selectedQuadrant.coordinates[2])
          if (findResult != null) {
            return findResult
          } else {
            break
          }
        }
      }
    }

    return null
  }
}
