package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSubzonesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingZonesDao
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.tracking.model.ShapefileFeature
import jakarta.inject.Named
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.InstantSource
import java.util.EnumSet
import kotlin.math.min
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.GeodeticCalculator
import org.jooq.DSLContext
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

@Named
class PlantingSiteImporter(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val monitoringPlotsDao: MonitoringPlotsDao,
    private val plantingSitesDao: PlantingSitesDao,
    private val plantingZonesDao: PlantingZonesDao,
    private val plantingSubzonesDao: PlantingSubzonesDao,
) {
  companion object {
    const val PLOT_NAME_PROPERTY = "monitoring"
    const val SUBZONE_NAME_PROPERTY = "planting_1"
    const val TARGET_PLANTING_DENSITY_PROPERTY = "plant_dens"
    const val ZONE_NAME_PROPERTY = "planting_z"

    /**
     * Minimum percentage of a zone or subzone that has to overlap with a neighboring one in order
     * to trip the validation check for overlapping areas. This fuzz factor is needed to account for
     * floating-point inaccuracy.
     */
    const val OVERLAP_MIN_PERCENT = 0.01

    /**
     * Minimum percentage of a zone or subzone that has to fall outside the boundaries of its parent
     * in order to trip the validation check for areas being contained in their parents. This fuzz
     * factor is needed to account for floating-point inaccuracy.
     */
    const val OUTSIDE_BOUNDS_MIN_PERCENT = 0.01

    /** Number of digits after the decimal point to retain in area (hectares) calculations. */
    const val HECTARES_SCALE = 1

    // Default values of the three parameters that determine how many monitoring plots should be
    // required in each observation. The "Student's t" value is a constant based on an 80%
    // confidence level and should rarely need to change, but the other two will be adjusted by
    // admins based on the conditions at the planting site. These defaults mean that planting zones
    // will have 7 permanent clusters and 9 temporary plots.
    val DEFAULT_ERROR_MARGIN = BigDecimal(100)
    val DEFAULT_STUDENTS_T = BigDecimal("1.282")
    val DEFAULT_VARIANCE = BigDecimal(40000)
    const val DEFAULT_NUM_PERMANENT_CLUSTERS = 7
    const val DEFAULT_NUM_TEMPORARY_PLOTS = 9

    /** Target planting density to use if not included in zone properties. */
    val DEFAULT_TARGET_PLANTING_DENSITY = BigDecimal(1500)

    const val AZIMUTH_EAST: Double = 90.0
    const val AZIMUTH_NORTH: Double = 0.0

    private val log = perClassLogger()
  }

  fun import(
      name: String,
      description: String? = null,
      organizationId: OrganizationId,
      shapefiles: Collection<Shapefile>,
      validationOptions: Set<ValidationOption> = EnumSet.allOf(ValidationOption::class.java),
  ): PlantingSiteId {
    requirePermissions { createPlantingSite(organizationId) }

    if (shapefiles.size != 3) {
      throw PlantingSiteUploadProblemsException(
          "Expected 3 shapefiles (site, zones, subzones) but found ${shapefiles.size}")
    }

    var siteFile: Shapefile? = null
    var zonesFile: Shapefile? = null
    var subzonesFile: Shapefile? = null

    shapefiles.forEach { shapefile ->
      when {
        shapefile.features.any { SUBZONE_NAME_PROPERTY in it.properties } ->
            subzonesFile = shapefile
        shapefile.features.any { ZONE_NAME_PROPERTY in it.properties } -> zonesFile = shapefile
        shapefile.features.size == 1 -> siteFile = shapefile
      }
    }

    return importShapefiles(
        name,
        description,
        organizationId,
        siteFile
            ?: throw PlantingSiteUploadProblemsException(
                "Planting site shapefile must contain exactly one geometry"),
        zonesFile
            ?: throw PlantingSiteUploadProblemsException(
                "Planting zones shapefile features must include $ZONE_NAME_PROPERTY property"),
        subzonesFile
            ?: throw PlantingSiteUploadProblemsException(
                "Subzones shapefile features must include $SUBZONE_NAME_PROPERTY property"),
        validationOptions)
  }

  /**
   * Imports a planting site from site, zone, and subzone shapefiles.
   *
   * @param fitSiteToPlots If true, treat the site, zone, and subzone boundaries as approximations;
   *   generate a set of plots that approximately fit in the boundaries (with up to 5% of a plot
   *   allowed to fall outside the boundary) and then fit the site, zone, and subzone boundaries to
   *   match the generated plots. This is used when creating a minimal-sized planting site from the
   *   admin UI.
   */
  fun importShapefiles(
      name: String,
      description: String? = null,
      organizationId: OrganizationId,
      siteFile: Shapefile,
      zonesFile: Shapefile,
      subzonesFile: Shapefile,
      validationOptions: Set<ValidationOption> = EnumSet.allOf(ValidationOption::class.java),
      fitSiteToPlots: Boolean = false,
  ): PlantingSiteId {
    requirePermissions { createPlantingSite(organizationId) }

    val coveragePercent = if (fitSiteToPlots) 95.0 else 100.0
    val problems = mutableListOf<String>()
    val siteFeature = getSiteBoundary(siteFile, validationOptions, problems)
    val zonesByName = getZones(siteFeature, zonesFile, validationOptions, problems)
    val subzonesByZone = getSubzonesByZone(zonesByName, subzonesFile, validationOptions, problems)
    val plotBoundaries = generatePlotBoundaries(siteFeature, coveragePercent)

    val totalPlots = plotBoundaries.sumOf { it.size }
    val totalPermanentClusters = plotBoundaries.count { it.size == 4 }

    // Need a minimum of 1 permanent cluster and 1 temporary plot.
    if (totalPlots < 5 || totalPermanentClusters == 0) {
      problems.add("Could not create enough monitoring plots (is the site at least 100x50 meters?)")
    }

    if (problems.isNotEmpty()) {
      throw PlantingSiteUploadProblemsException(problems)
    }

    val now = clock.instant()
    val userId = currentUser().userId

    return dslContext.transactionResult { _ ->
      val plotGeometries = plotBoundaries.flatten().map { it.boundary!! }
      val siteBoundary =
          if (fitSiteToPlots) {
            siteFeature.geometry.fitToPlots(plotGeometries)
          } else {
            siteFeature.geometry
          }

      val sitesRow =
          PlantingSitesRow(
              areaHa = scale(siteFeature.calculateAreaHectares()),
              boundary = siteBoundary,
              createdBy = userId,
              createdTime = now,
              description = description,
              modifiedBy = userId,
              modifiedTime = now,
              name = name,
              organizationId = organizationId,
          )

      plantingSitesDao.insert(sitesRow)
      val siteId = sitesRow.id!!

      zonesByName.forEach { (zoneName, zoneFeature) ->
        val targetPlantingDensity =
            zoneFeature.properties[TARGET_PLANTING_DENSITY_PROPERTY]?.toBigDecimalOrNull()
                ?: DEFAULT_TARGET_PLANTING_DENSITY

        val zoneBoundary =
            if (fitSiteToPlots) {
              zoneFeature.geometry.fitToPlots(plotGeometries)
            } else {
              zoneFeature.geometry
            }

        val zonesRow =
            PlantingZonesRow(
                areaHa = scale(zoneFeature.calculateAreaHectares()),
                boundary = zoneBoundary,
                createdBy = userId,
                createdTime = now,
                errorMargin = DEFAULT_ERROR_MARGIN,
                modifiedBy = userId,
                modifiedTime = now,
                name = zoneName,
                numPermanentClusters = min(DEFAULT_NUM_PERMANENT_CLUSTERS, totalPermanentClusters),
                numTemporaryPlots =
                    min(DEFAULT_NUM_TEMPORARY_PLOTS, totalPlots - totalPermanentClusters * 4),
                plantingSiteId = siteId,
                studentsT = DEFAULT_STUDENTS_T,
                targetPlantingDensity = targetPlantingDensity,
                variance = DEFAULT_VARIANCE,
            )

        plantingZonesDao.insert(zonesRow)

        subzonesByZone[zoneName]?.let { subzoneFeatures ->
          val subzoneRows =
              subzoneFeatures.map { subzoneFeature ->
                val subzoneName = subzoneFeature.properties[SUBZONE_NAME_PROPERTY]!!
                val fullSubzoneName = getFullSubzoneName(subzoneFeature)

                val subzoneBoundary =
                    if (fitSiteToPlots) {
                      subzoneFeature.geometry.fitToPlots(plotGeometries)
                    } else {
                      subzoneFeature.geometry
                    }

                val plantingSubzonesRow =
                    PlantingSubzonesRow(
                        areaHa = scale(subzoneFeature.calculateAreaHectares()),
                        boundary = subzoneBoundary,
                        createdBy = userId,
                        createdTime = now,
                        fullName = fullSubzoneName,
                        modifiedBy = userId,
                        modifiedTime = now,
                        name = subzoneName,
                        plantingSiteId = siteId,
                        plantingZoneId = zonesRow.id,
                    )

                plantingSubzonesDao.insert(plantingSubzonesRow)

                plantingSubzonesRow
              }

          val plots =
              assignPlots(zoneFeature.geometry, plotBoundaries, subzoneRows, coveragePercent)

          monitoringPlotsDao.insert(plots)
        }
      }

      log.info("Imported planting site $siteId for organization $organizationId")
      siteId
    }
  }

  private fun getSiteBoundary(
      siteFile: Shapefile,
      validationOptions: Set<ValidationOption>,
      problems: MutableList<String>,
  ): ShapefileFeature {
    if (siteFile.features.isEmpty()) {
      throw PlantingSiteUploadProblemsException(
          "Planting site shapefile does not contain a boundary")
    }

    if (ValidationOption.SiteIsMultiPolygon in validationOptions && siteFile.features.size > 1) {
      problems += "Planting site shapefile must contain 1 geometry; found ${siteFile.features.size}"
    }

    return if (siteFile.features.size == 1) {
      siteFile.features.first()
    } else {
      if (ValidationOption.SiteIsMultiPolygon in validationOptions) {
        problems +=
            "Planting site shapefile must contain 1 geometry; found ${siteFile.features.size}"
      }

      // Merge all the geometries together into one MultiPolygon.
      val geometry =
          siteFile.features
              .map { feature ->
                when (val geometry = feature.geometry) {
                  is MultiPolygon -> {
                    geometry
                  }
                  is Polygon -> {
                    if (ValidationOption.SiteIsMultiPolygon in validationOptions) {
                      problems += "Planting site geometry must be a MultiPolygon, not a Polygon"
                    }
                    MultiPolygon(arrayOf(geometry), GeometryFactory())
                  }
                  else -> {
                    throw PlantingSiteUploadProblemsException(
                        "Planting site geometry must be a MultiPolygon, not a ${geometry.geometryType}")
                  }
                }
              }
              .reduce { acc, next -> acc.union(next) as MultiPolygon }

      ShapefileFeature(
          geometry, siteFile.features[0].properties, siteFile.features[0].coordinateReferenceSystem)
    }
  }

  private fun getZones(
      siteFeature: ShapefileFeature,
      zonesFile: Shapefile,
      validationOptions: Set<ValidationOption>,
      problems: MutableList<String>,
  ): Map<String, ShapefileFeature> {
    if (zonesFile.features.isEmpty()) {
      throw IllegalArgumentException("No planting zones defined")
    }

    val zoneNames =
        zonesFile.features.map {
          it.properties[ZONE_NAME_PROPERTY]
              ?: throw IllegalArgumentException(
                  "Planting zone is missing $ZONE_NAME_PROPERTY property")
        }

    zoneNames
        .groupBy { it.lowercase() }
        .values
        .filter { it.size > 1 }
        .forEach {
          throw IllegalArgumentException("Planting zone ${it[0]} appears ${it.size} times")
        }

    if (ValidationOption.ZonesContainedInSite in validationOptions) {
      zonesFile.features.forEach { feature ->
        checkCoveredBy(feature, siteFeature, problems) { percent ->
          val zoneName = feature.properties[ZONE_NAME_PROPERTY]

          "$percent of planting zone $zoneName is not contained within planting site"
        }
      }
    }

    if (ValidationOption.ZonesDoNotOverlap in validationOptions) {
      checkOverlap(zonesFile.features, problems) { feature, otherFeature, overlapPercent ->
        val featureName = feature.properties[PLOT_NAME_PROPERTY]
        val otherName = otherFeature.properties[PLOT_NAME_PROPERTY]

        "$overlapPercent of plot $featureName overlaps with plot $otherName"
      }
    }

    if (ValidationOption.ZonesHaveTargetDensity in validationOptions) {
      zonesFile.features.forEach { feature ->
        val zoneName = feature.properties[ZONE_NAME_PROPERTY]

        if (TARGET_PLANTING_DENSITY_PROPERTY !in feature.properties) {
          problems += "Planting zone $zoneName has no target planting density"
        } else {
          val targetDensityString = feature.properties[TARGET_PLANTING_DENSITY_PROPERTY]!!
          try {
            BigDecimal(targetDensityString)
          } catch (e: NumberFormatException) {
            problems += "Planting zone $zoneName target planting density is not a number"
          }
        }
      }
    }

    return zonesFile.features.associate { feature ->
      val name = feature.properties[ZONE_NAME_PROPERTY]!!

      when (val geometry = feature.geometry) {
        is MultiPolygon -> {
          name to feature
        }
        is Polygon -> {
          problems += "Planting zone $name should be a MultiPolygon, not a Polygon"
          name to
              ShapefileFeature(
                  MultiPolygon(arrayOf(geometry), GeometryFactory()),
                  feature.properties,
                  feature.coordinateReferenceSystem)
        }
        else -> {
          throw IllegalArgumentException(
              "Planting zone $name geometry must be a MultiPolygon, not a ${geometry.geometryType}")
        }
      }
    }
  }

  private fun getSubzonesByZone(
      zones: Map<String, ShapefileFeature>,
      subzonesFile: Shapefile,
      validationOptions: Set<ValidationOption>,
      problems: MutableList<String>,
  ): Map<String, List<ShapefileFeature>> {
    val validSubzones =
        subzonesFile.features.filter { feature ->
          val subzoneName = feature.properties[SUBZONE_NAME_PROPERTY]
          val zoneName = feature.properties[ZONE_NAME_PROPERTY]

          if (subzoneName == null || subzoneName == "") {
            problems += "Subzone is missing $SUBZONE_NAME_PROPERTY property"
            false
          } else if (zoneName == null || zoneName == "") {
            problems += "Subzone $subzoneName is missing $ZONE_NAME_PROPERTY property"
            false
          } else if (zoneName !in zones) {
            problems +=
                "Subzone $subzoneName has zone $zoneName which does not appear in zones shapefile"
            false
          } else {
            true
          }
        }

    val subzonesByZone =
        validSubzones.groupBy { feature ->
          val zoneName = feature.properties[ZONE_NAME_PROPERTY]!!

          if (ValidationOption.SubzonesContainedInZone in validationOptions) {
            checkCoveredBy(feature, zones[zoneName]!!, problems) { percent ->
              val subzoneName = feature.properties[SUBZONE_NAME_PROPERTY]!!

              "$percent of subzone $subzoneName is not contained within zone $zoneName"
            }
          }

          zoneName
        }

    if (ValidationOption.SubzonesDoNotOverlap in validationOptions) {
      checkOverlap(validSubzones, problems) { feature, otherFeature, overlapPercent ->
        val featureName = feature.properties[SUBZONE_NAME_PROPERTY]
        val otherName = otherFeature.properties[SUBZONE_NAME_PROPERTY]

        "$overlapPercent of subzone $featureName overlaps with subzone $otherName"
      }
    }

    zones.keys.forEach { zoneName ->
      if (ValidationOption.ZonesHaveSubzones in validationOptions && zoneName !in subzonesByZone) {
        problems += "Zone $zoneName has no subzones"
      }
    }

    return subzonesByZone
  }

  /**
   * Assigns monitoring plots to subzones and to clusters of permanent monitoring plots.
   *
   * The permanent monitoring plots for a planting zone must always come in clusters of 4, and those
   * clusters of 4 must be selected at random. The number of permanent monitoring plots can vary
   * from one observation to the next, but the plots that are selected have to be consistent over
   * time.
   *
   * We guarantee this consistency by randomizing the list of eligible clusters at import time and
   * recording the randomized order in the form of a cluster number on each monitoring plot. The
   * cluster number starts at 1 for each planting zone, and there are always exactly 4 plots with a
   * given cluster number in a given planting zone.
   *
   * The cluster numbers allow permanent monitoring plots to be allocated using a SQL query. If we
   * want 3 clusters of permanent plots from planting zone 4321, we can do a query along the lines
   * of
   *
   *     SELECT mp.id, mp.permanent_cluster, mp.permanent_cluster_subplot
   *     FROM tracking.monitoring_plots mp
   *     JOIN tracking.planting_subzones ps ON mp.planting_subzone_id = ps.id
   *     WHERE ps.planting_zone_id = 4321
   *     AND mp.permanent_cluster <= 3
   *     ORDER BY mp.permanent_cluster, mp.permanent_cluster_subplot;
   *
   * and get back a result like
   *
   * | id   | cluster | subplot |
   * |------|---------|---------|
   * | 1756 | 1       | 1       |
   * | 1757 | 1       | 2       |
   * | 1758 | 1       | 3       |
   * | 1759 | 1       | 4       |
   * | 781  | 2       | 1       |
   * | 782  | 2       | 2       |
   * | 783  | 2       | 3       |
   * | 784  | 2       | 4       |
   * | 360  | 3       | 1       |
   * | 361  | 3       | 2       |
   * | 362  | 3       | 3       |
   * | 363  | 3       | 4       |
   */
  private fun assignPlots(
      zoneBoundary: Geometry,
      allClusters: Collection<Cluster>,
      subzoneRows: Collection<PlantingSubzonesRow>,
      coveragePercent: Double,
  ): List<MonitoringPlotsRow> {
    val now = clock.instant()
    val userId = currentUser().userId

    if (subzoneRows.map { it.plantingZoneId }.distinct().size != 1) {
      throw IllegalArgumentException("BUG! Plots must be assigned one zone at a time.")
    }

    // Determine which subzone each monitoring plot should be associated with. If a subzone border
    // runs through a monitoring plot, we choose whichever subzone contains the biggest percentage
    // of the monitoring plot.
    //
    // A cluster may contain monitoring plots in different subzones; this is normal and expected.
    val subzonePlotNumbers = mutableMapOf<PlantingSubzonesRow, Int>()
    val clustersInSubzones: List<Cluster> =
        allClusters.mapNotNull { cluster ->
          cluster.mapPlots { plotsRow ->
            val plotBoundary = plotsRow.boundary!!

            if (plotBoundary.overlapPercent(zoneBoundary) >= coveragePercent) {
              val parentSubzone =
                  subzoneRows
                      .mapNotNull { row ->
                        val coveredArea = row.boundary!!.intersection(plotBoundary).area
                        if (coveredArea > 0) {
                          row to coveredArea
                        } else {
                          null
                        }
                      }
                      .maxByOrNull { (_, area) -> area }
                      ?.first

              if (parentSubzone != null) {
                val plotNumber = subzonePlotNumbers.merge(parentSubzone, 1, Int::plus)

                plotsRow.copy(
                    createdBy = userId,
                    createdTime = now,
                    fullName = "${parentSubzone.fullName}-$plotNumber",
                    modifiedBy = userId,
                    modifiedTime = now,
                    name = "$plotNumber",
                    plantingSubzoneId = parentSubzone.id!!,
                )
              } else {
                null
              }
            } else {
              null
            }
          }
        }

    subzonePlotNumbers.forEach { (subzoneRow, count) ->
      log.debug("Assigned $count plots to subzone ${subzoneRow.fullName}")
    }

    // Now we have a list of clusters of monitoring plots. Add permanent monitoring cluster numbers
    // in random order to the clusters with 4 plots.
    val permanentClusters =
        clustersInSubzones
            .filter { it.size == 4 }
            .shuffled()
            .mapIndexed { clusterIndex, plotsRows ->
              plotsRows.mapIndexed { subplotIndex, plotsRow ->
                plotsRow.copy(
                    permanentCluster = clusterIndex + 1,
                    permanentClusterSubplot = subplotIndex + 1,
                )
              }
            }

    // Plots in clusters with fewer than 4 plots can still be selected as temporary plots.
    val temporaryOnlyClusters = clustersInSubzones.filter { it.size < 4 }

    return (permanentClusters + temporaryOnlyClusters).flatten()
  }

  /**
   * Generates a list of all potential monitoring plots for an entire planting site, clustered in
   * 2x2 groups. Only plots that fall within the planting site are included.
   *
   * This effectively divides the site into a grid of 50m squares, divides each square into four 25m
   * squares, and returns a polygon for each of the smaller squares.
   */
  private fun generatePlotBoundaries(
      siteFeature: ShapefileFeature,
      coveragePercent: Double
  ): List<Cluster> {
    val clusters = mutableListOf<Cluster>()
    val crs = siteFeature.coordinateReferenceSystem
    val calculator = GeodeticCalculator(crs)
    val factory = GeometryFactory(PrecisionModel(), siteFeature.geometry.srid)
    val envelope = siteFeature.geometry.envelope as Polygon
    val siteWest = envelope.coordinates[0]!!.x
    val siteSouth = envelope.coordinates[0]!!.y
    val siteEast = envelope.coordinates[2]!!.x
    val siteNorth = envelope.coordinates[2]!!.y

    var clusterSouth = siteSouth

    while (clusterSouth < siteNorth) {
      var clusterWest = siteWest

      calculator.setStartingPosition(
          JTS.toDirectPosition(Coordinate(clusterWest, clusterSouth), crs))
      calculator.setDirection(AZIMUTH_NORTH, MONITORING_PLOT_SIZE)
      val middleY = calculator.destinationPosition.getOrdinate(1)
      calculator.setStartingPosition(calculator.destinationPosition)
      calculator.setDirection(AZIMUTH_NORTH, MONITORING_PLOT_SIZE)
      val clusterNorth = calculator.destinationPosition.getOrdinate(1)

      while (clusterWest < siteEast) {
        calculator.setDirection(AZIMUTH_EAST, MONITORING_PLOT_SIZE)
        val middleX = calculator.destinationPosition.getOrdinate(0)
        calculator.setStartingPosition(calculator.destinationPosition)
        calculator.setDirection(AZIMUTH_EAST, MONITORING_PLOT_SIZE)
        val clusterEast = calculator.destinationPosition.getOrdinate(0)
        calculator.setStartingPosition(calculator.destinationPosition)

        fun createSquare(west: Double, south: Double, east: Double, north: Double) =
            factory.createPolygon(
                arrayOf(
                    Coordinate(west, south),
                    Coordinate(east, south),
                    Coordinate(east, north),
                    Coordinate(west, north),
                    Coordinate(west, south)))

        val plotsRows =
            listOf(
                    // The order is important here: southwest, southeast, northeast, northwest
                    // (the position in this list turns into the cluster subplot number).
                    createSquare(clusterWest, clusterSouth, middleX, middleY),
                    createSquare(middleX, clusterSouth, clusterEast, middleY),
                    createSquare(middleX, middleY, clusterEast, clusterNorth),
                    createSquare(clusterWest, middleY, middleX, clusterNorth),
                )
                .filter { it.overlapPercent(siteFeature.geometry) >= coveragePercent }
                .map { MonitoringPlotsRow(boundary = it) }

        if (plotsRows.isNotEmpty()) {
          clusters.add(Cluster(plotsRows))
        }

        clusterWest = clusterEast
      }

      clusterSouth = clusterNorth
    }

    return clusters
  }

  private fun checkCoveredBy(
      child: ShapefileFeature,
      parent: ShapefileFeature,
      problems: MutableList<String>,
      problemFunc: (String) -> String
  ) {
    if (!child.geometry.coveredBy(parent.geometry)) {
      val difference = child.geometry.difference(parent.geometry)
      val childArea = child.geometry.area
      val differenceArea = difference.area
      val uncoveredPercent = differenceArea / childArea * 100.0

      if (uncoveredPercent > OUTSIDE_BOUNDS_MIN_PERCENT) {
        val problem = problemFunc("%.02f%%".format(uncoveredPercent))
        problems += problem

        log.debug(problem)
        log.debug("Parent: ${parent.geometry}")
        log.debug("Child: ${child.geometry}")
        log.debug("Difference: $difference")
      }
    }
  }

  private fun checkOverlap(
      features: List<ShapefileFeature>,
      problems: MutableList<String>,
      problemFunc: (ShapefileFeature, ShapefileFeature, String) -> String
  ) {
    features.forEachIndexed { index, feature ->
      features.drop(index + 1).forEach { otherFeature ->
        val geometry = feature.geometry
        val otherGeometry = otherFeature.geometry
        if (geometry.overlaps(otherGeometry)) {
          val overlapPercent = geometry.overlapPercent(otherGeometry)

          if (overlapPercent > OVERLAP_MIN_PERCENT) {
            val problem = problemFunc(feature, otherFeature, "%.02f%%".format(overlapPercent))
            problems += problem

            log.debug(problem)
          }
        }
      }
    }
  }

  private fun Geometry.overlapPercent(otherGeometry: Geometry): Double =
      if (covers(otherGeometry)) 100.0 else intersection(otherGeometry).area / area * 100.0

  private fun Geometry.fitToPlots(plots: List<Geometry>): MultiPolygon {
    val fitted = plots.filter { it.overlapPercent(this) >= 50.0 }.reduce(Geometry::union)
    return if (fitted is MultiPolygon) {
      fitted
    } else if (fitted is Polygon) {
      GeometryFactory(PrecisionModel(), fitted.srid).createMultiPolygon(arrayOf(fitted))
    } else {
      throw IllegalArgumentException(
          "Fitted geometry is ${fitted.javaClass.simpleName}, not Polygon")
    }
  }

  private fun getFullSubzoneName(feature: ShapefileFeature): String =
      "${feature.properties[ZONE_NAME_PROPERTY]!!}-${feature.properties[SUBZONE_NAME_PROPERTY]!!}"

  enum class ValidationOption(val displayName: String) {
    SiteIsMultiPolygon("Site boundary is a single MultiPolygon"),
    SubzonesContainedInZone("Subzones are contained in their zones"),
    SubzonesDoNotOverlap("Subzones do not overlap"),
    ZonesContainedInSite("Zones are contained in the site"),
    ZonesDoNotOverlap("Zones do not overlap"),
    ZonesHaveSubzones("Zones have at least one subzone each"),
    ZonesHaveTargetDensity("Zones must have target planting densities"),
  }

  private fun scale(value: Double) =
      BigDecimal(value).setScale(HECTARES_SCALE, RoundingMode.HALF_EVEN)

  /**
   * A cluster of up to four monitoring plots. This is a simple wrapper around a list; it's purely
   * for purposes of code clarity.
   */
  private class Cluster(private val plots: List<MonitoringPlotsRow>) :
      List<MonitoringPlotsRow> by plots {
    fun mapPlots(func: (MonitoringPlotsRow) -> MonitoringPlotsRow?): Cluster? {
      val rows = plots.mapNotNull(func)
      return if (rows.isNotEmpty()) {
        Cluster(rows)
      } else {
        null
      }
    }
  }
}
