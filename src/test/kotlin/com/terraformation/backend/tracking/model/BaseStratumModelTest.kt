package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.toMultiPolygon
import java.math.BigDecimal
import java.time.Instant
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

/** Geometry and model fixtures shared by the stratum-level plot selection tests. */
abstract class BaseStratumModelTest {
  protected val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
  protected val siteOrigin = geometryFactory.createPoint(Coordinate(12.3, 45.6))

  /**
   * Returns the boundary of a test monitoring plot based on its ID. The 10s digit of the ID is
   * assumed to be the substratum ID and the 1s digit is assumed to be a position in the substratum.
   * The positions are laid out as follows:
   *
   *     4
   *     3  2
   *     0  1
   */
  protected fun monitoringPlotBoundary(id: Int): Polygon {
    val substratumId = id / 10
    val plotNumber = id.rem(10)

    return Turtle(siteOrigin).makePolygon {
      // Substratum corner
      east(substratumId * MONITORING_PLOT_SIZE * 2)
      north(MONITORING_PLOT_SIZE * (plotNumber / 2))
      if (plotNumber.rem(2) == 1) {
        east(MONITORING_PLOT_SIZE)
      }

      square(MONITORING_PLOT_SIZE)
    }
  }

  protected fun monitoringPlotModel(
      id: Int = 1,
      boundary: Polygon = monitoringPlotBoundary(id),
      elevationMeters: BigDecimal? = null,
      isAvailable: Boolean = true,
      permanentIndex: Int? = null,
  ): MonitoringPlotModel {
    return MonitoringPlotModel(
        boundary = boundary,
        elevationMeters = elevationMeters,
        id = MonitoringPlotId(id.toLong()),
        isAdHoc = false,
        isAvailable = isAvailable,
        permanentIndex = permanentIndex,
        plotNumber = id.toLong(),
        sizeMeters = MONITORING_PLOT_SIZE_INT,
    )
  }

  protected fun monitoringPlotIds(vararg id: Int) = id.map { MonitoringPlotId(it.toLong()) }.toSet()

  protected fun monitoringPlotModels(
      permanentIds: List<Int> = emptyList(),
      temporaryIds: List<Int> = emptyList(),
  ): List<MonitoringPlotModel> {
    return permanentIds.map { monitoringPlotModel(id = it, permanentIndex = 1) } +
        temporaryIds.map { monitoringPlotModel(id = it, permanentIndex = null) }
  }

  /**
   * Returns the boundary for a sample substratum. Substrata are arranged in a row from west to east
   * and each one has room for 5 monitoring plots in the layout defined by [monitoringPlotBoundary],
   * plus a 1-meter margin to account for floating-point inaccuracy.
   */
  protected fun substratumBoundary(id: Int, numPlots: Int): MultiPolygon {
    return Turtle(siteOrigin).makeMultiPolygon {
      east(id * MONITORING_PLOT_SIZE * 2)

      val southwest = currentPosition

      // Figure out the northwest corner's location.
      north(((numPlots + 1) / 2) * MONITORING_PLOT_SIZE)
      val northwest = currentPosition

      moveTo(southwest)

      startDrawing()
      east(MONITORING_PLOT_SIZE * 2)
      north((numPlots / 2) * MONITORING_PLOT_SIZE)

      if (numPlots.and(1) == 0) {
        // Even number of plots; the boundary is a plain rectangle.
        moveTo(northwest)
      } else {
        // Odd number of plots; space for the last plot is on the west half of the northern edge.
        west(MONITORING_PLOT_SIZE)
        north(MONITORING_PLOT_SIZE)
        moveTo(northwest)
      }
    }
  }

  protected fun substratumModel(
      id: Int = 1,
      plots: List<MonitoringPlotModel> = emptyList(),
      boundary: MultiPolygon = substratumBoundary(id, plots.size),
  ) =
      SubstratumModel(
          areaHa = BigDecimal.ONE,
          boundary = boundary,
          id = SubstratumId(id.toLong()),
          fullName = "name",
          name = "name",
          plantingCompletedTime = null,
          monitoringPlots = plots,
          stableId = StableId("name"),
      )

  protected fun substrataIds(vararg id: Int) = id.map { SubstratumId(it.toLong()) }.toSet()

  /**
   * Returns the boundary for a sample stratum that contains some number of 51x76 meter substrata
   * laid out west to east.
   */
  protected fun stratumBoundary(substrata: List<ExistingSubstratumModel>): MultiPolygon {
    if (substrata.isEmpty()) {
      return multiPolygon(1)
    }

    return substrata
        .map { it.boundary }
        .reduce { acc: Geometry, substratum: Geometry -> acc.union(substratum) }
        .toMultiPolygon()
  }

  protected fun stratumModel(
      numTemporaryPlots: Int = 1,
      numPermanentPlots: Int = 1,
      substrata: List<ExistingSubstratumModel>,
      boundary: MultiPolygon = stratumBoundary(substrata),
  ) =
      ExistingStratumModel(
          areaHa = BigDecimal.ONE,
          boundary = boundary,
          boundaryModifiedTime = Instant.EPOCH,
          errorMargin = BigDecimal.ONE,
          id = StratumId(1),
          initialPlantingDensity = BigDecimal.ONE,
          name = "name",
          numPermanentPlots = numPermanentPlots,
          numTemporaryPlots = numTemporaryPlots,
          substrata = substrata,
          stableId = StableId("name"),
          studentsT = BigDecimal.ONE,
          variance = BigDecimal.ONE,
      )
}
