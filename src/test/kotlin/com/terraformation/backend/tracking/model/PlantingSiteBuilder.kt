package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
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
 * DSL for building planting site models with simple maps to test operations on site/zone/subzone
 * geometry. The different layers of the map are all rectangular and are laid out horizontally
 * starting at the southwest corner of the parent area.
 *
 * The DSL aims for succinctness and will try to build a simple but complete site if the caller
 * doesn't specify otherwise.
 *
 * By default, each layer fills the remaining area of its parent. If a site has no zones, one is
 * created by default, and if a zone has no subzones, one is created by default.
 *
 * Intended usage is to import the [existingSite] and/or [newSite] functions and optionally pass
 * lambda functions to them to configure the child areas. Simple usage:
 *
 *     existingSite()
 *
 * Returns a planting site 500 by 500 meters, with a single zone 500 by 500 meters, with a single
 * subzone 500 by 500 meters.
 *
 * More complex usage:
 *
 *     existingSite(width = 1000) {
 *       zone(width = 400) {
 *         subzone(width = 150) {
 *           cluster()
 *           plot()
 *         }
 *         subzone()
 *       }
 *       zone()
 *     }
 *
 * Returns a planting site with:
 * - A boundary of 1000 by 500 meters
 * - Zone Z1 with a boundary of 400 by 500 meters whose southwest corner is the southwest corner of
 *   the site's boundary
 *     - Subzone S1 with a boundary of 150 by 500 meters whose southwest corner is the southwest
 *       corner of the zone (and thus of the site)
 *         - Cluster 1 with plots 1, 2, 3, 4 arranged counterclockwise starting at the southwest
 *           corner of the subzone, that is, at coordinates (0,0), (25,0), (25,25), and (0,25) in
 *           meters relative to the subzone's southwest corner
 *         - Plot 5 immediately to the east of plot 2, that is, at coordinates (50,0) relative to
 *           the subzone's southwest corner
 *     - Subzone S2 with a boundary of 250 by 500 meters, filling the remaining space in Z1
 * - Zone Z2 with a boundary of 600 by 500 meters (filling the remaining space in the site)
 *   immediately east of Z1, that is at coordinates (400,0) relative to the zone's southwest corner
 *     - Subzone S3 with the same boundary as Z2
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
        func: PlantingSiteBuilder.() -> Unit = {}
    ): NewPlantingSiteModel = existingSite(x, y, width, height, countryCode, func).toNew()
  }

  private val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)

  var boundary: MultiPolygon = rectangle(width, height, x, y)
  var exclusion: MultiPolygon? = null
  var gridOrigin: Point = geometryFactory.createPoint(boundary.envelope.coordinates[0])
  var name: String = "Site"
  var organizationId: OrganizationId = OrganizationId(-1)
  var nextPlotNumber: Long = 1

  private var currentSubzoneId: Long = 0
  private var currentZoneId: Long = 0
  private var nextZoneX = x
  private var nextMonitoringPlotX: Int = x + width
  private val exteriorPlots = mutableListOf<MonitoringPlotModel>()
  private val plantingZones = mutableListOf<ExistingPlantingZoneModel>()

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
        plantingZones = plantingZones.ifEmpty { listOf(zone()) },
    )
  }

  fun zone(
      x: Int = nextZoneX,
      y: Int = this.y,
      width: Int = this.width - (x - this.x),
      height: Int = this.height - (y - this.y),
      name: String? = null,
      numPermanent: Int = PlantingZoneModel.DEFAULT_NUM_PERMANENT_CLUSTERS,
      numTemporary: Int = PlantingZoneModel.DEFAULT_NUM_TEMPORARY_PLOTS,
      extraPermanent: Int = 0,
      func: ZoneBuilder.() -> Unit = {}
  ): ExistingPlantingZoneModel {
    ++currentZoneId

    val builder =
        ZoneBuilder(
            x = x,
            y = y,
            width = width,
            height = height,
            name = name ?: "Z$currentZoneId",
            numPermanentClusters = numPermanent,
            numTemporaryPlots = numTemporary,
            extraPermanentClusters = extraPermanent,
        )
    builder.func()

    nextZoneX = x + width

    val newZone = builder.build()
    plantingZones.add(newZone)
    return newZone
  }

  fun exteriorPlot(
      x: Int = nextMonitoringPlotX,
      y: Int = this.y,
      isAdHoc: Boolean = false,
      isAvailable: Boolean = true,
      size: Int = MONITORING_PLOT_SIZE_INT,
      plotNumber: Long = nextPlotNumber,
  ): MonitoringPlotModel {
    nextMonitoringPlotX = x + size

    val plot =
        MonitoringPlotModel(
            boundary = rectanglePolygon(size, size, x, y),
            id = MonitoringPlotId(plotNumber),
            isAdHoc = isAdHoc,
            isAvailable = isAvailable,
            permanentCluster = null,
            permanentClusterSubplot = null,
            plotNumber = plotNumber,
            sizeMeters = size,
        )

    nextPlotNumber++

    exteriorPlots.add(plot)
    return plot
  }

  inner class ZoneBuilder(
      private val x: Int,
      private val y: Int,
      private val width: Int,
      private val height: Int,
      private val name: String,
      private val numPermanentClusters: Int = PlantingZoneModel.DEFAULT_NUM_PERMANENT_CLUSTERS,
      private val numTemporaryPlots: Int = PlantingZoneModel.DEFAULT_NUM_TEMPORARY_PLOTS,
      private val extraPermanentClusters: Int = 0,
  ) {
    var errorMargin: BigDecimal = PlantingZoneModel.DEFAULT_ERROR_MARGIN
    var studentsT: BigDecimal = PlantingZoneModel.DEFAULT_STUDENTS_T
    var targetPlantingDensity: BigDecimal = PlantingZoneModel.DEFAULT_TARGET_PLANTING_DENSITY
    var variance: BigDecimal = PlantingZoneModel.DEFAULT_VARIANCE

    private val boundary: MultiPolygon = rectangle(width, height, x, y)
    private var nextPermanentCluster = 1
    private var nextSubzoneX = x
    private val plantingSubzones = mutableListOf<ExistingPlantingSubzoneModel>()

    fun build(): ExistingPlantingZoneModel {
      return ExistingPlantingZoneModel(
          areaHa = boundary.differenceNullable(exclusion).calculateAreaHectares(),
          boundary = boundary,
          boundaryModifiedTime = Instant.EPOCH,
          errorMargin = errorMargin,
          extraPermanentClusters = extraPermanentClusters,
          id = PlantingZoneId(currentZoneId),
          name = name,
          numPermanentClusters = numPermanentClusters,
          numTemporaryPlots = numTemporaryPlots,
          plantingSubzones = plantingSubzones.ifEmpty { listOf(subzone()) },
          studentsT = studentsT,
          targetPlantingDensity = targetPlantingDensity,
          variance = variance,
      )
    }

    fun subzone(
        x: Int = nextSubzoneX,
        y: Int = this.y,
        width: Int = this.width - (x - this.x),
        height: Int = this.height - (y - this.y),
        name: String? = null,
        func: SubzoneBuilder.() -> Unit = {},
    ): ExistingPlantingSubzoneModel {
      ++currentSubzoneId

      val builder = SubzoneBuilder(x, y, width, height, name ?: "S$currentSubzoneId", this.name)
      builder.func()

      nextSubzoneX = x + width

      val newSubzone = builder.build()
      plantingSubzones.add(newSubzone)
      return newSubzone
    }

    inner class SubzoneBuilder(
        x: Int,
        private val y: Int,
        width: Int,
        height: Int,
        val name: String,
        zoneName: String,
    ) {
      private val boundary: MultiPolygon = rectangle(width, height, x, y)
      private val fullName: String = "$zoneName-$name"
      private var lastCluster: Int? = null
      private val monitoringPlots = mutableListOf<MonitoringPlotModel>()
      private var nextMonitoringPlotX: Int = x
      private var nextSubplot: Int = 1

      var plantingCompletedTime: Instant? = null

      fun build(): ExistingPlantingSubzoneModel {
        return ExistingPlantingSubzoneModel(
            areaHa = boundary.differenceNullable(exclusion).calculateAreaHectares(),
            boundary = boundary,
            fullName = fullName,
            id = PlantingSubzoneId(currentSubzoneId),
            monitoringPlots = monitoringPlots,
            name = name,
            plantingCompletedTime = plantingCompletedTime,
        )
      }

      fun plot(
          x: Int = nextMonitoringPlotX,
          y: Int = this.y,
          cluster: Int? = null,
          subplot: Int? =
              when (cluster) {
                null -> null
                lastCluster -> nextSubplot
                else -> 1
              },
          isAdHoc: Boolean = false,
          isAvailable: Boolean = true,
          size: Int = MONITORING_PLOT_SIZE_INT,
          plotNumber: Long = nextPlotNumber,
      ): MonitoringPlotModel {
        lastCluster = cluster
        nextMonitoringPlotX = x + size

        if (subplot != null) {
          nextSubplot = subplot + 1
        }

        val plot =
            MonitoringPlotModel(
                boundary = rectanglePolygon(size, size, x, y),
                id = MonitoringPlotId(plotNumber),
                isAdHoc = isAdHoc,
                isAvailable = isAvailable,
                permanentCluster = cluster,
                permanentClusterSubplot = subplot,
                plotNumber = plotNumber,
                sizeMeters = size,
            )

        nextPlotNumber++

        monitoringPlots.add(plot)
        return plot
      }

      fun cluster(
          x: Int = nextMonitoringPlotX,
          y: Int = this.y,
          cluster: Int = nextPermanentCluster++,
          isAvailable: Boolean = true,
          plotNumber: Long = nextPlotNumber,
      ): MonitoringPlotModel {
        return plot(x, y, cluster, isAvailable = isAvailable, plotNumber = plotNumber)
      }
    }
  }
}
