package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSubzonesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingZonesDao
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.tracking.model.ShapefileFeature
import java.time.InstantSource
import java.util.EnumSet
import javax.inject.Named
import org.jooq.DSLContext
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon

@Named
class PlantingSiteImporter(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val plantingSitesDao: PlantingSitesDao,
    private val plantingZonesDao: PlantingZonesDao,
    private val plantingSubzonesDao: PlantingSubzonesDao,
) {
  companion object {
    const val PLOT_NAME_PROPERTY = "Plot"
    const val ZONE_NAME_PROPERTY = "Zone"

    /**
     * Minimum percentage of a plot or zone that has to overlap with a neighboring one in order to
     * trip the validation check for overlapping areas. This fuzz factor is needed to account for
     * floating-point inaccuracy.
     */
    const val OVERLAP_MIN_PERCENT = 0.01

    /**
     * Minimum percentage of a plot or zone that has to fall outside the boundaries of its parent in
     * order to trip the validation check for areas being contained in their parents. This fuzz
     * factor is needed to account for floating-point inaccuracy.
     */
    const val OUTSIDE_BOUNDS_MIN_PERCENT = 0.01

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
          "Expected 3 shapefiles (site, zones, plots) but found ${shapefiles.size}")
    }

    var siteFile: Shapefile? = null
    var zonesFile: Shapefile? = null
    var plotsFile: Shapefile? = null

    shapefiles.forEach { shapefile ->
      when {
        shapefile.features.any { PLOT_NAME_PROPERTY in it.properties } -> plotsFile = shapefile
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
        plotsFile
            ?: throw PlantingSiteUploadProblemsException(
                "Plots shapefile features must include $PLOT_NAME_PROPERTY property"),
        validationOptions)
  }

  private fun importShapefiles(
      name: String,
      description: String? = null,
      organizationId: OrganizationId,
      siteFile: Shapefile,
      zonesFile: Shapefile,
      plotsFile: Shapefile,
      validationOptions: Set<ValidationOption> = EnumSet.allOf(ValidationOption::class.java),
  ): PlantingSiteId {
    requirePermissions { createPlantingSite(organizationId) }

    val problems = mutableListOf<String>()
    val siteFeature = getSiteBoundary(siteFile, validationOptions, problems)
    val zonesByName = getZones(siteFeature, zonesFile, validationOptions, problems)
    val plotsByZone = getPlotsByZone(zonesByName, plotsFile, validationOptions, problems)

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

      val zonesRows =
          zonesByName.mapValues { (name, zoneFeature) ->
            val zonesRow =
                PlantingZonesRow(
                    boundary = zoneFeature.geometry,
                    createdBy = userId,
                    createdTime = now,
                    modifiedBy = userId,
                    modifiedTime = now,
                    name = name,
                    plantingSiteId = siteId,
                )

            plantingZonesDao.insert(zonesRow)

            zonesRow
          }

      plotsByZone.forEach { (zoneName, features) ->
        val zoneId = zonesRows[zoneName]!!.id!!

        features.forEach { feature ->
          val plotName = feature.properties[PLOT_NAME_PROPERTY]!!
          val fullName = "$zoneName-$plotName"

          val plotsRow =
              PlantingSubzonesRow(
                  boundary = feature.geometry,
                  createdBy = userId,
                  createdTime = now,
                  fullName = fullName,
                  modifiedBy = userId,
                  modifiedTime = now,
                  name = plotName,
                  plantingSiteId = siteId,
                  plantingZoneId = zoneId,
              )

          plantingSubzonesDao.insert(plotsRow)
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

      ShapefileFeature(geometry, siteFile.features[0].properties)
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

      if (ValidationOption.ZonesHaveSingleLetterNames in validationOptions &&
          (name.length != 1 || !name[0].isUpperCase())) {
        problems += "Planting zone name $name is not a single upper-case letter"
      }

      when (val geometry = feature.geometry) {
        is MultiPolygon -> {
          name to feature
        }
        is Polygon -> {
          problems += "Planting zone $name should be a MultiPolygon, not a Polygon"
          name to
              ShapefileFeature(
                  MultiPolygon(arrayOf(geometry), GeometryFactory()), feature.properties)
        }
        else -> {
          throw IllegalArgumentException(
              "Planting zone $name geometry must be a MultiPolygon, not a ${geometry.geometryType}")
        }
      }
    }
  }

  private fun getPlotsByZone(
      zones: Map<String, ShapefileFeature>,
      plotsFile: Shapefile,
      validationOptions: Set<ValidationOption>,
      problems: MutableList<String>,
  ): Map<String, List<ShapefileFeature>> {
    val validPlots =
        plotsFile.features.filter { feature ->
          val plotName = feature.properties[PLOT_NAME_PROPERTY]
          val zoneName = feature.properties[ZONE_NAME_PROPERTY]

          if (plotName == null) {
            problems += "Plot is missing $PLOT_NAME_PROPERTY property"
            false
          } else {
            if (ValidationOption.PlotsHaveNumericNames in validationOptions &&
                plotName.toIntOrNull() == null) {
              problems += "Plot $plotName does not have a numeric name"
            }

            when (zoneName) {
              null,
              "" -> {
                if (ValidationOption.PlotsHaveZones in validationOptions) {
                  problems += "Plot $plotName is missing $ZONE_NAME_PROPERTY property"
                }
                false
              }
              !in zones -> {
                if (ValidationOption.PlotsHaveZones in validationOptions) {
                  problems +=
                      "Plot $plotName has zone $zoneName which does not appear in zones shapefile"
                }
                false
              }
              else -> {
                true
              }
            }
          }
        }

    val plotsByZone =
        validPlots.groupBy { feature ->
          val zoneName = feature.properties[ZONE_NAME_PROPERTY]!!

          if (ValidationOption.PlotsContainedInZone in validationOptions) {
            checkCoveredBy(feature, zones[zoneName]!!, problems) { percent ->
              val plotName = feature.properties[PLOT_NAME_PROPERTY]!!

              "$percent of plot $plotName is not contained within zone $zoneName"
            }
          }

          zoneName
        }

    if (ValidationOption.PlotsDoNotOverlap in validationOptions) {
      checkOverlap(validPlots, problems) { feature, otherFeature, overlapPercent ->
        val featureName = feature.properties[PLOT_NAME_PROPERTY]
        val otherName = otherFeature.properties[PLOT_NAME_PROPERTY]

        "$overlapPercent of plot $featureName overlaps with plot $otherName"
      }
    }

    zones.keys.forEach { zoneName ->
      if (ValidationOption.ZonesHavePlots in validationOptions && zoneName !in plotsByZone) {
        problems += "Zone $zoneName has no plots"
      }
    }

    return plotsByZone
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

  enum class ValidationOption(val displayName: String) {
    PlotsContainedInZone("Plots are contained in their zones"),
    PlotsDoNotOverlap("Plots do not overlap"),
    PlotsHaveNumericNames("Plots have numeric names"),
    PlotsHaveZones("Plots have zone names"),
    SiteIsMultiPolygon("Site boundary is a single MultiPolygon"),
    ZonesContainedInSite("Zones are contained in the site"),
    ZonesDoNotOverlap("Zones do not overlap"),
    ZonesHavePlots("Zones have at least one plot each"),
    ZonesHaveSingleLetterNames("Zones have uppercase 1-letter names"),
  }
}
