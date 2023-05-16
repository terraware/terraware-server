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
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.tracking.model.ShapefileFeature
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.InstantSource
import java.util.EnumSet
import javax.inject.Named
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.GeodeticCalculator
import org.jooq.DSLContext
import org.locationtech.jts.geom.Coordinate
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

    /** Monitoring plot width and height in meters. */
    const val MONITORING_PLOT_SIZE: Double = 25.0

    /** Number of digits after the decimal point to retain in area (hectares) calculations. */
    const val HECTARES_SCALE = 1

    // Default values of the three parameters that determine how many monitoring plots should be
    // required in each observation. The "Student's t" value is a constant based on a 90% confidence
    // level and should rarely need to change, but the other two will be adjusted by admins based on
    // the conditions at the planting site. These defaults mean that planting zones will have 12
    // permanent clusters and 16 temporary plots.
    val DEFAULT_ERROR_MARGIN = BigDecimal(100)
    val DEFAULT_STUDENTS_T = BigDecimal("1.645")
    val DEFAULT_VARIANCE = BigDecimal(40000)
    const val DEFAULT_NUM_PERMANENT_CLUSTERS = 11
    const val DEFAULT_NUM_TEMPORARY_PLOTS = 14

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

  private fun importShapefiles(
      name: String,
      description: String? = null,
      organizationId: OrganizationId,
      siteFile: Shapefile,
      zonesFile: Shapefile,
      subzonesFile: Shapefile,
      validationOptions: Set<ValidationOption> = EnumSet.allOf(ValidationOption::class.java),
  ): PlantingSiteId {
    requirePermissions { createPlantingSite(organizationId) }

    val problems = mutableListOf<String>()
    val siteFeature = getSiteBoundary(siteFile, validationOptions, problems)
    val zonesByName = getZones(siteFeature, zonesFile, validationOptions, problems)
    val subzonesByZone = getSubzonesByZone(zonesByName, subzonesFile, validationOptions, problems)
    val plotBoundaries = generatePlotBoundaries(siteFeature)

    if (problems.isNotEmpty()) {
      throw PlantingSiteUploadProblemsException(problems)
    }

    val now = clock.instant()
    val userId = currentUser().userId

    return dslContext.transactionResult { _ ->
      val sitesRow =
          PlantingSitesRow(
              areaHa = scale(siteFeature.calculateAreaHectares()),
              boundary = siteFeature.geometry,
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
        val zonesRow =
            PlantingZonesRow(
                areaHa = scale(zoneFeature.calculateAreaHectares()),
                boundary = zoneFeature.geometry,
                createdBy = userId,
                createdTime = now,
                errorMargin = DEFAULT_ERROR_MARGIN,
                modifiedBy = userId,
                modifiedTime = now,
                name = zoneName,
                numPermanentClusters = DEFAULT_NUM_PERMANENT_CLUSTERS,
                numTemporaryPlots = DEFAULT_NUM_TEMPORARY_PLOTS,
                plantingSiteId = siteId,
                studentsT = DEFAULT_STUDENTS_T,
                variance = DEFAULT_VARIANCE,
            )

        plantingZonesDao.insert(zonesRow)

        subzonesByZone[zoneName]?.let { subzoneFeatures ->
          val subzoneRows =
              subzoneFeatures.map { subzoneFeature ->
                val subzoneName = subzoneFeature.properties[SUBZONE_NAME_PROPERTY]!!
                val fullSubzoneName = getFullSubzoneName(subzoneFeature)

                val plantingSubzonesRow =
                    PlantingSubzonesRow(
                        areaHa = scale(subzoneFeature.calculateAreaHectares()),
                        boundary = subzoneFeature.geometry,
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

          val plots = assignPlots(plotBoundaries, subzoneRows)

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
            if (ValidationOption.SubzonesHaveNumericNames in validationOptions &&
                subzoneName.toIntOrNull() == null) {
              problems += "Subzone $subzoneName in zone $zoneName does not have a numeric name"
            }

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
   * If all 4 plots in a cluster fall in the same subzone, they are eligible to be used as permanent
   * monitoring plots.
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
      allClusters: Collection<Cluster>,
      subzoneRows: Collection<PlantingSubzonesRow>,
  ): List<MonitoringPlotsRow> {
    val now = clock.instant()
    val userId = currentUser().userId

    if (subzoneRows.map { it.plantingZoneId }.distinct().size != 1) {
      throw IllegalArgumentException("BUG! Plots must be assigned one zone at a time.")
    }

    // Eliminate any monitoring plots that straddle subzone boundaries, and record which subzone
    // each plot is in as well as its plot number within that subzone. Plots are still grouped in
    // 2x2 clusters at this point (though a given cluster might have fewer than 4 plots if some of
    // them didn't fall within the subzone).
    val clustersInSubzones: List<Cluster> =
        subzoneRows.flatMap { subzoneRow ->
          val subzoneBoundary = subzoneRow.boundary!!
          var plotNumber = 0

          val subzoneClusters =
              allClusters
                  .map { cluster -> cluster.filter { it.boundary!!.coveredBy(subzoneBoundary) } }
                  .filter { it.isNotEmpty() }
                  .map { cluster ->
                    cluster.map { plotsRow ->
                      plotNumber++

                      plotsRow.copy(
                          createdBy = userId,
                          createdTime = now,
                          fullName = "${subzoneRow.fullName}-$plotNumber",
                          modifiedBy = userId,
                          modifiedTime = now,
                          name = "$plotNumber",
                          plantingSubzoneId = subzoneRow.id,
                      )
                    }
                  }

          log.debug("Assigned $plotNumber plots to subzone ${subzoneRow.fullName}")

          subzoneClusters
        }

    // Now we have a list of clusters of monitoring plots, where each cluster's plots are all in
    // the same subzone. Add permanent monitoring cluster numbers in random order to all
    // the 4-plot clusters.
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

    val nonPermanentClusters = clustersInSubzones.filter { it.size != 4 }

    return (permanentClusters + nonPermanentClusters).flatten()
  }

  /**
   * Generates a list of all potential monitoring plots for an entire planting site, clustered in
   * 2x2 groups. Only plots that fall within the planting site are included.
   *
   * This effectively divides the site into a grid of 50m squares, divides each square into four 25m
   * squares, and returns a polygon for each of the smaller squares.
   */
  private fun generatePlotBoundaries(siteFeature: ShapefileFeature): List<Cluster> {
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
                .filter { it.coveredBy(siteFeature.geometry) }
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
          val overlap = geometry.intersection(otherGeometry)
          val overlapPercent = overlap.area / geometry.area * 100.0

          if (overlapPercent > OVERLAP_MIN_PERCENT) {
            val problem = problemFunc(feature, otherFeature, "%.02f%%".format(overlapPercent))
            problems += problem

            log.debug(problem)
            log.debug("Geometry 1: $geometry")
            log.debug("Geometry 2: $otherGeometry")
            log.debug("Overlap: $overlap")
          }
        }
      }
    }
  }

  private fun getFullSubzoneName(feature: ShapefileFeature): String =
      "${feature.properties[ZONE_NAME_PROPERTY]!!}-${feature.properties[SUBZONE_NAME_PROPERTY]!!}"

  enum class ValidationOption(val displayName: String) {
    SiteIsMultiPolygon("Site boundary is a single MultiPolygon"),
    SubzonesContainedInZone("Subzones are contained in their zones"),
    SubzonesDoNotOverlap("Subzones do not overlap"),
    SubzonesHaveNumericNames("Subzones have numeric names"),
    ZonesContainedInSite("Zones are contained in the site"),
    ZonesDoNotOverlap("Zones do not overlap"),
    ZonesHaveSubzones("Zones have at least one subzone each"),
  }

  private fun scale(value: Double) =
      BigDecimal(value).setScale(HECTARES_SCALE, RoundingMode.HALF_EVEN)

  /**
   * A cluster of up to four monitoring plots. This is a simple wrapper around a list; it's purely
   * for purposes of code clarity.
   */
  private class Cluster(private val plots: List<MonitoringPlotsRow>) :
      List<MonitoringPlotsRow> by plots {
    fun filter(func: (MonitoringPlotsRow) -> Boolean) = Cluster(plots.filter(func))
    fun map(func: (MonitoringPlotsRow) -> MonitoringPlotsRow) = Cluster(plots.map(func))
  }
}
