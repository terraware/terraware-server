package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.rectangle
import com.terraformation.backend.rectanglePolygon
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.differenceNullable
import java.math.BigDecimal
import java.time.Instant
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel

/**
 * DSL for building planting site models with simple maps to test operations on
 * site/stratum/substratum geometry. The different layers of the map are all rectangular and are
 * laid out horizontally starting at the southwest corner of the parent area.
 *
 * The DSL aims for succinctness and will try to build a simple but complete site if the caller
 * doesn't specify otherwise.
 *
 * By default, each layer fills the remaining area of its parent. If a site has no strata, one is
 * created by default, and if a stratum has no substrata, one is created by default.
 *
 * Intended usage is to import the [existingSite] and/or [newSite] functions and optionally pass
 * lambda functions to them to configure the child areas. Simple usage:
 *
 *     existingSite()
 *
 * Returns a planting site 500 by 500 meters, with a single stratum 500 by 500 meters, with a single
 * substratum 500 by 500 meters.
 *
 * More complex usage:
 *
 *     existingSite(width = 1000) {
 *       stratum(width = 400) {
 *         substratum(width = 150) {
 *           permanent()
 *           plot()
 *         }
 *         substratum()
 *       }
 *       stratum()
 *     }
 *
 * Returns a planting site with:
 * - A boundary of 1000 by 500 meters
 * - Stratum Z1 with a boundary of 400 by 500 meters whose southwest corner is the southwest corner
 *   of the site's boundary
 *     - Substratum S1 with a boundary of 150 by 500 meters whose southwest corner is the southwest
 *       corner of the stratum (and thus of the site)
 *         - Plot 1 with permanent index 1
 *         - Plot 2 immediately to the east of plot 1, that is, at coordinates (30,0) relative to
 *           the substratum's southwest corner
 *     - Substratum S2 with a boundary of 250 by 500 meters, filling the remaining space in Z1
 * - Stratum Z2 with a boundary of 600 by 500 meters (filling the remaining space in the site)
 *   immediately east of Z1, that is at coordinates (400,0) relative to the stratum's southwest
 *   corner
 *     - Substratum S3 with the same boundary as Z2
 */
class PlantingSiteBuilder
private constructor(
    private val x: Int,
    private val y: Int,
    private val width: Int,
    private val height: Int,
    private val countryCode: String?,
) {
  companion object {
    /**
     * Returns a planting site where all the component parts have non-null IDs. See
     * [PlantingSiteBuilder] for usage.
     */
    fun existingSite(
        x: Int = 0,
        y: Int = 0,
        width: Int = 500,
        height: Int = 500,
        countryCode: String? = null,
        func: PlantingSiteBuilder.() -> Unit = {},
    ): ExistingPlantingSiteModel {
      val builder = PlantingSiteBuilder(x, y, width, height, countryCode)
      builder.func()
      return builder.build()
    }

    /**
     * Returns a planting site where all of the component parts have null IDs. Monitoring plots are
     * discarded. See [PlantingSiteBuilder] for usage.
     */
    fun newSite(
        x: Int = 0,
        y: Int = 0,
        width: Int = 500,
        height: Int = 500,
        countryCode: String? = null,
        func: PlantingSiteBuilder.() -> Unit = {},
    ): NewPlantingSiteModel = existingSite(x, y, width, height, countryCode, func).toNew()
  }

  private val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)

  var boundary: MultiPolygon = rectangle(width, height, x, y)
  var exclusion: MultiPolygon? = null
  var gridOrigin: Point = geometryFactory.createPoint(boundary.envelope.coordinates[0])
  var name: String = "Site"
  var organizationId: OrganizationId = OrganizationId(-1)
  var nextPlotNumber: Long = 1

  private var currentStratumId: Long = 1
  private var currentSubstratumId: Long = 1
  private var nextStratumX = x
  private var nextMonitoringPlotX: Int = x + width
  private val exteriorPlots = mutableListOf<MonitoringPlotModel>()
  private val strata = mutableListOf<ExistingStratumModel>()

  fun build(): ExistingPlantingSiteModel {
    return ExistingPlantingSiteModel(
        areaHa = boundary.differenceNullable(exclusion).calculateAreaHectares(),
        boundary = boundary,
        countryCode = countryCode,
        exclusion = exclusion,
        exteriorPlots = exteriorPlots,
        gridOrigin = gridOrigin,
        id = PlantingSiteId(1),
        name = name,
        organizationId = organizationId,
        strata = strata.ifEmpty { listOf(stratum()) },
    )
  }

  fun stratum(
      x: Int = nextStratumX,
      y: Int = this.y,
      width: Int = this.width - (x - this.x),
      height: Int = this.height - (y - this.y),
      name: String = "S$currentStratumId",
      numPermanent: Int = StratumModel.DEFAULT_NUM_PERMANENT_PLOTS,
      numTemporary: Int = StratumModel.DEFAULT_NUM_TEMPORARY_PLOTS,
      stableId: StableId = StableId(name),
      func: StratumBuilder.() -> Unit = {},
  ): ExistingStratumModel {
    ++currentStratumId

    val builder =
        StratumBuilder(
            x = x,
            y = y,
            width = width,
            height = height,
            name = name,
            numPermanentPlots = numPermanent,
            numTemporaryPlots = numTemporary,
            stableId = stableId,
        )
    builder.func()

    nextStratumX = x + width

    val newStratum = builder.build()
    strata.add(newStratum)
    return newStratum
  }

  fun exteriorPlot(
      x: Int = nextMonitoringPlotX,
      y: Int = this.y,
      elevationMeters: BigDecimal? = null,
      isAdHoc: Boolean = false,
      isAvailable: Boolean = true,
      size: Int = MONITORING_PLOT_SIZE_INT,
      plotNumber: Long = nextPlotNumber,
  ): MonitoringPlotModel {
    nextMonitoringPlotX = x + size

    val plot =
        MonitoringPlotModel(
            boundary = rectanglePolygon(size, size, x, y),
            elevationMeters = elevationMeters,
            id = MonitoringPlotId(plotNumber),
            isAdHoc = isAdHoc,
            isAvailable = isAvailable,
            permanentIndex = null,
            plotNumber = plotNumber,
            sizeMeters = size,
        )

    nextPlotNumber++

    exteriorPlots.add(plot)
    return plot
  }

  inner class StratumBuilder(
      private val x: Int,
      private val y: Int,
      private val width: Int,
      private val height: Int,
      private val name: String,
      private val numPermanentPlots: Int = StratumModel.DEFAULT_NUM_PERMANENT_PLOTS,
      private val numTemporaryPlots: Int = StratumModel.DEFAULT_NUM_TEMPORARY_PLOTS,
      private val stableId: StableId = StableId(name),
  ) {
    var errorMargin: BigDecimal = StratumModel.DEFAULT_ERROR_MARGIN
    var studentsT: BigDecimal = StratumModel.DEFAULT_STUDENTS_T
    var targetPlantingDensity: BigDecimal = StratumModel.DEFAULT_TARGET_PLANTING_DENSITY
    var variance: BigDecimal = StratumModel.DEFAULT_VARIANCE

    private val boundary: MultiPolygon = rectangle(width, height, x, y)
    private var nextPermanentIndex = 1
    private var nextSubstratumX = x
    private val substrata = mutableListOf<ExistingSubstratumModel>()

    fun build(): ExistingStratumModel {
      return ExistingStratumModel(
          areaHa = boundary.differenceNullable(exclusion).calculateAreaHectares(),
          boundary = boundary,
          boundaryModifiedTime = Instant.EPOCH,
          errorMargin = errorMargin,
          id = StratumId(currentStratumId),
          name = name,
          numPermanentPlots = numPermanentPlots,
          numTemporaryPlots = numTemporaryPlots,
          substrata = substrata.ifEmpty { listOf(substratum()) },
          stableId = stableId,
          studentsT = studentsT,
          targetPlantingDensity = targetPlantingDensity,
          variance = variance,
      )
    }

    fun substratum(
        x: Int = nextSubstratumX,
        y: Int = this.y,
        width: Int = this.width - (x - this.x),
        height: Int = this.height - (y - this.y),
        name: String = "Sub$currentSubstratumId",
        fullName: String = "${this.name}-$name",
        stableId: StableId = StableId(fullName),
        func: SubstratumBuilder.() -> Unit = {},
    ): ExistingSubstratumModel {
      ++currentSubstratumId

      val builder = SubstratumBuilder(x, y, width, height, name, fullName, stableId)
      builder.func()

      nextSubstratumX = x + width

      val newSubstratum = builder.build()
      substrata.add(newSubstratum)
      return newSubstratum
    }

    inner class SubstratumBuilder(
        x: Int,
        private val y: Int,
        width: Int,
        height: Int,
        val name: String,
        private val fullName: String,
        private val stableId: StableId = StableId(fullName),
    ) {
      private val boundary: MultiPolygon = rectangle(width, height, x, y)
      private var lastIndex: Int? = null
      private val monitoringPlots = mutableListOf<MonitoringPlotModel>()
      private var nextMonitoringPlotX: Int = x

      var plantingCompletedTime: Instant? = null

      fun build(): ExistingSubstratumModel {
        return ExistingSubstratumModel(
            areaHa = boundary.differenceNullable(exclusion).calculateAreaHectares(),
            boundary = boundary,
            fullName = fullName,
            id = SubstratumId(currentSubstratumId),
            monitoringPlots = monitoringPlots,
            name = name,
            plantingCompletedTime = plantingCompletedTime,
            stableId = stableId,
        )
      }

      fun plot(
          x: Int = nextMonitoringPlotX,
          y: Int = this.y,
          permanentIndex: Int? = null,
          elevationMeters: BigDecimal? = null,
          isAdHoc: Boolean = false,
          isAvailable: Boolean = true,
          size: Int = MONITORING_PLOT_SIZE_INT,
          plotNumber: Long = nextPlotNumber,
      ): MonitoringPlotModel {
        lastIndex = permanentIndex
        nextMonitoringPlotX = x + size

        val plot =
            MonitoringPlotModel(
                boundary = rectanglePolygon(size, size, x, y),
                elevationMeters = elevationMeters,
                id = MonitoringPlotId(plotNumber),
                isAdHoc = isAdHoc,
                isAvailable = isAvailable,
                permanentIndex = permanentIndex,
                plotNumber = plotNumber,
                sizeMeters = size,
            )

        nextPlotNumber++

        monitoringPlots.add(plot)
        return plot
      }

      fun permanent(
          x: Int = nextMonitoringPlotX,
          y: Int = this.y,
          index: Int = nextPermanentIndex++,
          isAvailable: Boolean = true,
          plotNumber: Long = nextPlotNumber,
      ): MonitoringPlotModel {
        return plot(x, y, index, isAvailable = isAvailable, plotNumber = plotNumber)
      }
    }
  }
}
