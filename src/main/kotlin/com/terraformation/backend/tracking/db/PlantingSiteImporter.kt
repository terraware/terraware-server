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
    val plotsBySubzone = generatePlotsBySubzone(siteFeature, subzonesFile)

    if (problems.isNotEmpty()) {
      throw PlantingSiteUploadProblemsException(problems)
    }

    val now = clock.instant()
    val userId = currentUser().userId

    return dslContext.transactionResult { _ ->
      val sitesRow =
          PlantingSitesRow(
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
                boundary = zoneFeature.geometry,
                createdBy = userId,
                createdTime = now,
                modifiedBy = userId,
                modifiedTime = now,
                name = zoneName,
                plantingSiteId = siteId,
            )

        plantingZonesDao.insert(zonesRow)

        subzonesByZone[zoneName]?.forEach { subzoneFeature ->
          val subzoneName = subzoneFeature.properties[SUBZONE_NAME_PROPERTY]!!
          val fullSubzoneName = getFullSubzoneName(subzoneFeature)

          val plantingSubzonesRow =
              PlantingSubzonesRow(
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

          plotsBySubzone[fullSubzoneName]?.forEach { plotFeature ->
            val plotName = plotFeature.properties[PLOT_NAME_PROPERTY]!!
            val fullPlotName = "$fullSubzoneName-$plotName"

            val plotsRow =
                MonitoringPlotsRow(
                    boundary = plotFeature.geometry,
                    createdBy = userId,
                    createdTime = now,
                    fullName = fullPlotName,
                    modifiedBy = userId,
                    modifiedTime = now,
                    name = plotName,
                    plantingSubzoneId = plantingSubzonesRow.id,
                )

            monitoringPlotsDao.insert(plotsRow)
          }
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

  /** Returns a map of full subzone names to the list of monitoring plots in each subzone. */
  private fun generatePlotsBySubzone(
      siteFeature: ShapefileFeature,
      subzonesFile: Shapefile,
  ): Map<String, List<ShapefileFeature>> {
    val crs = siteFeature.coordinateReferenceSystem
    val factory = GeometryFactory(PrecisionModel(), siteFeature.geometry.srid)
    val allPlots = generateAllPlots(siteFeature)

    return subzonesFile.features.associate { subzoneFeature ->
      val plots =
          allPlots
              .filter { it.coveredBy(subzoneFeature.geometry) }
              .mapIndexed { index, polygon ->
                ShapefileFeature(
                    factory.createMultiPolygon(arrayOf(polygon)),
                    mapOf(
                        PLOT_NAME_PROPERTY to "${index + 1}",
                        SUBZONE_NAME_PROPERTY to subzoneFeature.properties[SUBZONE_NAME_PROPERTY]!!,
                        ZONE_NAME_PROPERTY to subzoneFeature.properties[ZONE_NAME_PROPERTY]!!,
                    ),
                    crs)
              }

      log.debug("Generated ${plots.size} plots for subzone ${getFullSubzoneName(subzoneFeature)}")

      getFullSubzoneName(subzoneFeature) to plots
    }
  }

  /**
   * Generates a list of all potential monitoring plots for an entire planting site.
   *
   * This effectively divides the site into a grid of 25m squares and returns a polygon for each
   * square.
   */
  private fun generateAllPlots(siteFeature: ShapefileFeature): List<Polygon> {
    val plots = mutableListOf<Polygon>()
    val crs = siteFeature.coordinateReferenceSystem
    val calculator = GeodeticCalculator(crs)
    val factory = GeometryFactory(PrecisionModel(), siteFeature.geometry.srid)
    val envelope = siteFeature.geometry.envelope as Polygon
    val minX = envelope.coordinates[0]!!.x
    val minY = envelope.coordinates[0]!!.y
    val maxX = envelope.coordinates[2]!!.x
    val maxY = envelope.coordinates[2]!!.y

    var y = minY
    while (y < maxY) {
      var x = minX

      calculator.setStartingPosition(JTS.toDirectPosition(Coordinate(x, y), crs))
      calculator.setDirection(AZIMUTH_NORTH, MONITORING_PLOT_SIZE)
      val nextY = calculator.destinationPosition.getOrdinate(1)

      while (x < maxX) {
        calculator.setDirection(AZIMUTH_EAST, MONITORING_PLOT_SIZE)
        calculator.setStartingPosition(calculator.destinationPosition)
        val nextX = calculator.startingPosition.getOrdinate(0)

        val polygon =
            factory.createPolygon(
                arrayOf(
                    Coordinate(x, y),
                    Coordinate(nextX, y),
                    Coordinate(nextX, nextY),
                    Coordinate(x, nextY),
                    Coordinate(x, y),
                ))

        if (polygon.coveredBy(siteFeature.geometry)) {
          plots.add(polygon)
        }

        x = nextX
      }

      y = nextY
    }

    return plots
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
}
