package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import kotlin.random.Random
import org.geotools.api.referencing.crs.CoordinateReferenceSystem
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

data class PlantingZoneModel(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val errorMargin: BigDecimal,
    val extraPermanentClusters: Int,
    val id: PlantingZoneId,
    val name: String,
    val numPermanentClusters: Int,
    val numTemporaryPlots: Int,
    val plantingSubzones: List<PlantingSubzoneModel>,
    val studentsT: BigDecimal,
    val targetPlantingDensity: BigDecimal,
    val variance: BigDecimal,
) {
  /**
   * Chooses a set of plots to act as permanent monitoring plots. The number of plots is determined
   * by [numPermanentClusters] (where each cluster has 4 plots).
   *
   * Only clusters whose plots are all in planted subzones are returned, meaning there may be fewer
   * plots than configured, or none at all.
   */
  fun choosePermanentPlots(plantedSubzoneIds: Set<PlantingSubzoneId>): Set<MonitoringPlotId> {
    if (plantingSubzones.isEmpty()) {
      throw IllegalArgumentException("No subzones found for planting zone $id (wrong fetch depth?)")
    }

    val plantedSubzones = plantingSubzones.filter { it.id in plantedSubzoneIds }
    val plotsInPlantedSubzones =
        plantedSubzones.flatMap { subzone ->
          subzone.monitoringPlots.filter { plot ->
            plot.permanentCluster != null && plot.permanentCluster <= numPermanentClusters
          }
        }
    val clustersWithFourQualifiedPlots =
        plotsInPlantedSubzones.groupBy { it.permanentCluster }.values.filter { it.size == 4 }
    return clustersWithFourQualifiedPlots.flatMap { cluster -> cluster.map { it.id } }.toSet()
  }

  /**
   * Chooses a set of plots to act as temporary monitoring plots. The number of plots is determined
   * by [numTemporaryPlots].
   *
   * This follows a few rules:
   * - Plots that are already selected as permanent plots aren't eligible.
   * - Plots that span subzone boundaries aren't eligible.
   * - Plots must be spread across subzones as evenly as possible: the number of temporary plots
   *   can't vary by more than 1 between subzones.
   * - If plots can't be exactly evenly spread across subzones (that is, [numTemporaryPlots] is not
   *   a multiple of the number of subzones) the remaining ones must be placed in the subzones that
   *   have the fewest number of permanent plots. If multiple subzones have the same number of
   *   permanent plots (including 0 of them), temporary plots should be placed in subzones that have
   *   been planted before being "placed" in ones that haven't (but see the next point).
   * - Only subzones that have been planted should have temporary plots. However, they should be
   *   excluded only after all the preceding rules have been followed. That is, we want to choose
   *   plots based on the rules above, and then filter out plots in unplanted subzones, as opposed
   *   to only spreading plots across planted subzones. Otherwise, for a new project with a small
   *   number of planted subzones, we would end up piling the entire planting zone's worth of
   *   temporary plots into a handful of subzones.
   *
   * @throws IllegalArgumentException The number of temporary plots hasn't been configured or the
   *   planting zone has no subzones.
   * @throws PlantingSubzoneFullException There weren't enough eligible plots available in a subzone
   *   to choose the required number.
   */
  fun chooseTemporaryPlots(
      permanentPlotIds: Set<MonitoringPlotId>,
      plantedSubzoneIds: Set<PlantingSubzoneId>,
  ): Collection<MonitoringPlotId> {
    if (plantingSubzones.isEmpty()) {
      throw IllegalArgumentException("No subzones found for planting zone $id (wrong fetch depth?)")
    }

    // We will assign as many plots as possible evenly across all subzones.
    val numEvenlySpreadPlotsPerSubzone = numTemporaryPlots / plantingSubzones.size
    val numExcessPlots = numTemporaryPlots.rem(plantingSubzones.size)

    // Any plots that can't be spread evenly will be placed in the subzones with the smallest
    // number of permanent plots, with priority given to subzones that have been planted.
    //
    // If we sort the subzones by permanent plot count (always a multiple of 4) plus 1 if the
    // subzone has no plants, this means we can assign one extra plot each to the first N subzones
    // on that sorted list where N is the number of excess plots.
    return plantingSubzones
        .sortedBy { subzone ->
          val numPermanentPlots = subzone.monitoringPlots.count { it.id in permanentPlotIds }
          if (subzone.id in plantedSubzoneIds) numPermanentPlots else numPermanentPlots + 1
        }
        .flatMapIndexed { index, subzone ->
          if (subzone.id in plantedSubzoneIds) {
            val numPlots =
                if (index < numExcessPlots) {
                  numEvenlySpreadPlotsPerSubzone + 1
                } else {
                  numEvenlySpreadPlotsPerSubzone
                }

            val selectedPlots = subzone.chooseTemporaryPlots(permanentPlotIds, numPlots)

            if (selectedPlots.size < numPlots) {
              throw PlantingSubzoneFullException(subzone.id, numPlots, selectedPlots.size)
            }

            selectedPlots
          } else {
            // This subzone has no plants, so it gets no temporary plots.
            emptyList()
          }
        }
  }

  /**
   * Returns the planting subzone that contains a monitoring plot, or null if the plot isn't in any
   * of the subzones.
   */
  fun findSubzoneWithMonitoringPlot(monitoringPlotId: MonitoringPlotId): PlantingSubzoneModel? {
    return plantingSubzones.firstOrNull { subzone ->
      subzone.monitoringPlots.any { it.id == monitoringPlotId }
    }
  }

  /**
   * Returns a square Polygon of the requested width/height that is completely contained in the zone
   * and does not intersect with any existing monitoring plots.
   *
   * The square will be positioned a whole multiple of [gridInterval] from the specified origin.
   *
   * @param exclusion Areas to exclude from the zone, or null if the whole zone is available.
   * @return The unused square, or null if no suitable area could be found.
   */
  fun findUnusedSquare(
      gridOrigin: Point,
      sizeMeters: Int,
      exclusion: MultiPolygon? = null,
      gridInterval: Double = sizeMeters.toDouble()
  ): Polygon? {
    val geometryFactory = GeometryFactory(PrecisionModel())

    // We want to do the grid rounding in meters using a coordinate system that's centered on the
    // origin point of the original version of the zone's boundary to minimize distortion for zones
    // of reasonable size. So we need to transform to and from that coordinate system.
    val boundaryCrs = CRS.decode("EPSG:${boundary.srid}", true)
    val meterCrs = getMeterCoordinateSystem(gridOrigin)
    val toMeters = CRS.findMathTransform(boundaryCrs, meterCrs)

    // For purposes of checking whether or not a particular grid position is available, we treat
    // existing monitoring plots as part of the exclusion area.
    val monitoringPlotAreas = getMonitoringPlotGeometry()

    // The geometry of the zone's available area (minus exclusion and existing plots) in the
    // meter-based coordinate system.
    val zoneGeometry =
        if (exclusion != null) {
          JTS.transform(boundary.difference(exclusion).difference(monitoringPlotAreas), toMeters)
              as MultiPolygon
        } else {
          JTS.transform(boundary.difference(monitoringPlotAreas), toMeters) as MultiPolygon
        }

    fun rectangle(west: Double, south: Double, east: Double, north: Double): Polygon =
        geometryFactory.createPolygon(
            arrayOf(
                Coordinate(west, south),
                Coordinate(east, south),
                Coordinate(east, north),
                Coordinate(west, north),
                Coordinate(west, south)))

    fun roundToGrid(value: Double): Double = Math.round(value / gridInterval) * gridInterval

    /**
     * Returns a square [sizeMeters] on a side with the coordinates snapped to multiples of
     * [gridInterval].
     *
     * The grid lines the square is aligned to will skew the further away from the origin the
     * starting point is, but the square itself is generated using a geodetic calculation that
     * should produce a true axis-aligned square at any distance from the origin.
     */
    fun gridAlignedSquare(west: Double, south: Double): Polygon {
      val start = geometryFactory.createPoint(Coordinate(roundToGrid(west), roundToGrid(south)))

      return Turtle.makePolygon(start, meterCrs) {
        east(sizeMeters)
        north(sizeMeters)
        west(sizeMeters)
      }
    }

    /**
     * Converts a polygon's coordinates to the same coordinate reference system as the zone
     * boundary.
     */
    fun convertToBoundaryCrs(polygon: Polygon): Polygon {
      return (JTS.transform(polygon, CRS.findMathTransform(meterCrs, boundaryCrs)) as Polygon)
          .also { it.srid = boundary.srid }
    }

    /**
     * Attempts to find an available square within a rectangular region of the zone. First tries to
     * pick a few random points, and if none of them works, divides the region into four quadrants
     * and searches them in area-weighted random order until it finds a match.
     *
     * The idea is that for a typical zone, the initial random probe will most likely work and we'll
     * return a result quickly, but for an odd-shaped zone, we keep drilling down until we've done
     * an exhaustive search for matches.
     */
    fun findInRegion(southwest: Coordinate, northeast: Coordinate): Polygon? {
      val regionPolygon = rectangle(southwest.x, southwest.y, northeast.x, northeast.y)

      // If all possible points in the region will round to the same point on the grid, or if the
      // region is less than a quarter the size of a grid square, check it and return; trying random
      // points within the region wouldn't be useful, nor would dividing the region in quarters and
      // drilling down.
      val minimumArea = (gridInterval * gridInterval) / 4.0
      if (roundToGrid(southwest.x) == roundToGrid(northeast.x) &&
          roundToGrid(southwest.y) == roundToGrid(northeast.y) ||
          regionPolygon.area < minimumArea) {
        val polygon = gridAlignedSquare(southwest.x, southwest.y)

        return if (polygon.coveredBy(zoneGeometry)) {
          convertToBoundaryCrs(polygon)
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
                Random.nextDouble(southwest.y, northeast.y))

        if (polygon.coveredBy(zoneGeometry)) {
          return convertToBoundaryCrs(polygon)
        }
      }

      // No luck. This might be a sparse site map. Split the region into equal-sized quadrants and
      // search them in random order but with the ones that cover the most zone area more likely to
      // come first.
      val middleX = (southwest.x + northeast.x) / 2.0
      val middleY = (southwest.y + northeast.y) / 2.0

      // List of quadrant geometry and how much usable area the quadrant has.
      val quadrants: MutableList<Pair<Polygon, Double>> =
          listOf(
                  rectangle(southwest.x, southwest.y, middleX, middleY),
                  rectangle(middleX, southwest.y, northeast.x, middleY),
                  rectangle(middleX, middleY, northeast.x, northeast.y),
                  rectangle(southwest.x, middleY, middleX, northeast.y))
              .map { quadrant -> quadrant to zoneGeometry.intersection(quadrant).area }
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

    // Extend the initial search area beyond the actual zone envelope so that we're equally likely
    // to choose an edge location as an interior location.
    //
    // If we don't do this, then the interior grid lines are chosen when a random position is within
    // (sizeMeters / 2) in either direction, whereas an edge location is only chosen when the
    // position is within (sizeMeters / 2) in one direction because the points that would round
    // to it from the other direction would be outside the random number range.
    val zoneEnvelope = zoneGeometry.envelope
    val margin = sizeMeters / 2.0

    return findInRegion(
        Coordinate(zoneEnvelope.coordinates[0].x - margin, zoneEnvelope.coordinates[0].y - margin),
        Coordinate(zoneEnvelope.coordinates[2].x + margin, zoneEnvelope.coordinates[2].y + margin))
  }

  /** Returns a MultiPolygon that contains the boundaries of all the zone's monitoring plots. */
  private fun getMonitoringPlotGeometry(): MultiPolygon {
    val factory = GeometryFactory(PrecisionModel(), boundary.srid)

    return factory.createMultiPolygon(
        plantingSubzones.flatMap { it.monitoringPlots }.map { it.boundary }.toTypedArray())
  }

  /**
   * Returns a Cartesian coordinate reference system centered on an origin point where the units are
   * 1 meter.
   */
  private fun getMeterCoordinateSystem(origin: Point): CoordinateReferenceSystem {
    val originLongLat =
        if (origin.srid == SRID.LONG_LAT) {
          origin
        } else {
          val originCrs = CRS.decode("EPSG:${origin.srid}", true)
          val longLatCrs = CRS.decode("EPSG:${SRID.LONG_LAT}", true)
          JTS.transform(origin, CRS.findMathTransform(originCrs, longLatCrs)) as Point
        }
    return CRS.parseWKT(
        "PROJCS[\"Local Cartesian\"," +
            "GEOGCS[\"WGS 84\"," +
            "DATUM[\"WGS_1984\"," +
            "SPHEROID[\"WGS 84\",6378137,298.257223563]]," +
            "PRIMEM[\"Greenwich\",0]," +
            "UNIT[\"degree\",0.0174532925199433]]," +
            "PROJECTION[\"Orthographic\"]," +
            "PARAMETER[\"latitude_of_origin\",${originLongLat.y}]," +
            "PARAMETER[\"central_meridian\",${originLongLat.x}]," +
            "UNIT[\"m\",1.0]]")
  }

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingZoneModel &&
        id == other.id &&
        name == other.name &&
        extraPermanentClusters == other.extraPermanentClusters &&
        numPermanentClusters == other.numPermanentClusters &&
        numTemporaryPlots == other.numTemporaryPlots &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        errorMargin.equalsIgnoreScale(other.errorMargin) &&
        studentsT.equalsIgnoreScale(other.studentsT) &&
        variance.equalsIgnoreScale(other.variance) &&
        plantingSubzones.zip(other.plantingSubzones).all { (a, b) -> a.equals(b, tolerance) } &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}
