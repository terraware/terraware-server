package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.daos.PlantingSubzonesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingZonesDao
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.tracking.model.NewPlantingSubzoneModel
import com.terraformation.backend.tracking.model.NewPlantingZoneModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.tracking.model.ShapefileFeature
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.toMultiPolygon
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.InstantSource
import org.jooq.DSLContext
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

@Named
class PlantingSiteImporter(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val plantingSiteStore: PlantingSiteStore,
    private val plantingZonesDao: PlantingZonesDao,
    private val plantingSubzonesDao: PlantingSubzonesDao,
) {
  companion object {
    val siteNameProperties = setOf("planting_s", "site")
    val subzoneNameProperties = setOf("planting_1", "subzone")
    val targetPlantingDensityProperties = setOf("plan_dens", "density")
    val zoneNameProperties = setOf("planting_z", "zone")

    // Optional zone-level properties to set initial plot counts; mostly for testing
    val permanentClusterCountProperties = setOf("permanent")
    val temporaryPlotCountProperies = setOf("temporary")

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

    /** The maximum size of the envelope (bounding box) of a site. */
    val MAX_SITE_ENVELOPE_AREA_HA = BigDecimal(20000)

    private val log = perClassLogger()
  }

  fun import(
      name: String,
      description: String? = null,
      organizationId: OrganizationId,
      shapefiles: Collection<Shapefile>,
  ): PlantingSiteId {
    requirePermissions { createPlantingSite(organizationId) }

    if (shapefiles.size != 3 && shapefiles.size != 4) {
      throw PlantingSiteUploadProblemsException(
          "Expected 3 or 4 shapefiles (site, zones, subzones, and optionally exclusions) but " +
              "found ${shapefiles.size}")
    }

    var siteFile: Shapefile? = null
    var zonesFile: Shapefile? = null
    var subzonesFile: Shapefile? = null
    var exclusionsFile: Shapefile? = null

    shapefiles.forEach { shapefile ->
      if (shapefile.features.isEmpty()) {
        throw PlantingSiteUploadProblemsException("Shapefiles must contain geometries")
      }

      when {
        shapefile.features.all { it.hasProperty(subzoneNameProperties) } -> subzonesFile = shapefile
        shapefile.features.all { it.hasProperty(zoneNameProperties) } -> zonesFile = shapefile
        shapefile.features.size == 1 && shapefile.features[0].hasProperty(siteNameProperties) ->
            siteFile = shapefile
        else -> exclusionsFile = shapefile
      }
    }

    return importShapefiles(
        name,
        description,
        organizationId,
        siteFile
            ?: throw PlantingSiteUploadProblemsException(
                "Planting site shapefile must contain exactly one geometry and one of these properties: " +
                    siteNameProperties.joinToString()),
        zonesFile
            ?: throw PlantingSiteUploadProblemsException(
                "Planting zones shapefile features must include one of these properties: " +
                    zoneNameProperties.joinToString()),
        subzonesFile
            ?: throw PlantingSiteUploadProblemsException(
                "Subzones shapefile features must include one of these properties: " +
                    subzoneNameProperties.joinToString()),
        exclusionsFile)
  }

  /** Imports a planting site from site, zone, and subzone shapefiles. */
  fun importShapefiles(
      name: String,
      description: String? = null,
      organizationId: OrganizationId,
      siteFile: Shapefile,
      zonesFile: Shapefile,
      subzonesFile: Shapefile,
      exclusionsFile: Shapefile?,
  ): PlantingSiteId {
    requirePermissions { createPlantingSite(organizationId) }

    val problems = mutableListOf<String>()
    val siteFeature = getSiteBoundary(siteFile, problems)

    val siteAreaHa = siteFeature.calculateAreaHectares(siteFeature.geometry.envelope)
    if (siteAreaHa > MAX_SITE_ENVELOPE_AREA_HA) {
      problems.add(
          "Site must be contained within an envelope (rectangular area) of no more than " +
              "$MAX_SITE_ENVELOPE_AREA_HA hectares; actual envelope area was $siteAreaHa hectares.")
      throw PlantingSiteUploadProblemsException(problems)
    }

    val zonesByName = getZones(siteFeature, zonesFile, problems)
    val subzonesByZone = getSubzonesByZone(zonesByName, subzonesFile, problems)
    val exclusion = getExclusion(exclusionsFile, problems)

    val newModel =
        PlantingSiteModel.create(
            boundary = siteFeature.geometry.toMultiPolygon(),
            description = description,
            exclusion = exclusion,
            name = name,
            organizationId = organizationId,
            plantingZones =
                zonesByName.values.map { zone ->
                  zone.copy(plantingSubzones = subzonesByZone[zone.name] ?: emptyList())
                })

    if (problems.isNotEmpty()) {
      throw PlantingSiteUploadProblemsException(problems)
    }

    val now = clock.instant()
    val userId = currentUser().userId

    return dslContext.transactionResult { _ ->
      val siteId = plantingSiteStore.createPlantingSite(newModel).id

      newModel.plantingZones.forEach { zone ->
        val zonesRow =
            PlantingZonesRow(
                areaHa = zone.areaHa,
                boundary = zone.boundary,
                createdBy = userId,
                createdTime = now,
                errorMargin = zone.errorMargin,
                extraPermanentClusters = zone.extraPermanentClusters,
                modifiedBy = userId,
                modifiedTime = now,
                name = zone.name,
                numPermanentClusters = zone.numPermanentClusters,
                numTemporaryPlots = zone.numTemporaryPlots,
                plantingSiteId = siteId,
                studentsT = zone.studentsT,
                targetPlantingDensity = zone.targetPlantingDensity,
                variance = zone.variance,
            )

        plantingZonesDao.insert(zonesRow)

        zone.plantingSubzones.forEach { subzone ->
          val plantingSubzonesRow =
              PlantingSubzonesRow(
                  areaHa = subzone.areaHa,
                  boundary = subzone.boundary,
                  createdBy = userId,
                  createdTime = now,
                  fullName = subzone.fullName,
                  modifiedBy = userId,
                  modifiedTime = now,
                  name = subzone.name,
                  plantingSiteId = siteId,
                  plantingZoneId = zonesRow.id,
              )

          plantingSubzonesDao.insert(plantingSubzonesRow)
        }
      }

      // Verify that every zone is big enough to fit a permanent cluster and a temporary plot.
      val plantingSite = plantingSiteStore.fetchSiteById(siteId, PlantingSiteDepth.Subzone)
      plantingSite.plantingZones.forEach { plantingZone ->
        // Find 5 squares: 4 for the cluster and one for a temporary plot.
        val plotBoundaries =
            plantingZone.findUnusedSquares(
                count = 5,
                exclusion = plantingSite.exclusion,
                gridOrigin = plantingSite.gridOrigin!!,
            )

        // Make sure we have room for an actual cluster.
        val clusterBoundaries =
            plantingZone.findUnusedSquares(
                count = 1,
                exclusion = plantingSite.exclusion,
                gridOrigin = plantingSite.gridOrigin,
                sizeMeters = MONITORING_PLOT_SIZE * 2,
            )

        if (clusterBoundaries.isEmpty() || plotBoundaries.size < 5) {
          problems.add(
              "Could not create enough monitoring plots in zone ${plantingZone.name} " +
                  "(is the zone at least 150x75 meters?)")
        }
      }

      if (problems.isNotEmpty()) {
        throw PlantingSiteUploadProblemsException(problems)
      }

      log.info("Imported planting site $siteId for organization $organizationId")
      siteId
    }
  }

  private fun getSiteBoundary(
      siteFile: Shapefile,
      problems: MutableList<String>
  ): ShapefileFeature {
    if (siteFile.features.isEmpty()) {
      throw PlantingSiteUploadProblemsException(
          "Planting site shapefile does not contain a boundary")
    }

    if (siteFile.features.size > 1) {
      problems += "Planting site shapefile must contain 1 geometry; found ${siteFile.features.size}"
    }

    return siteFile.features.first()
  }

  /**
   * Returns a single MultiPolygon that combines all the Polygons and MultiPolygons in the
   * exclusions shapefile.
   */
  private fun getExclusion(
      exclusionsFile: Shapefile?,
      problems: MutableList<String>
  ): MultiPolygon? {
    if (exclusionsFile == null || exclusionsFile.features.isEmpty()) {
      return null
    }

    val allPolygons =
        exclusionsFile.features
            .map { it.geometry }
            .flatMap { geometry ->
              when (geometry) {
                is Polygon -> listOf(geometry)
                is MultiPolygon ->
                    (0 ..< geometry.numGeometries).map { geometry.getGeometryN(it) as Polygon }
                else -> {
                  problems.add("Exclusion geometries must all be Polygon or MultiPolygon.")
                  throw PlantingSiteUploadProblemsException(problems)
                }
              }
            }

    return geometryFactory(exclusionsFile.features.first())
        .createMultiPolygon(allPolygons.toTypedArray())
  }

  private fun getZones(
      siteFeature: ShapefileFeature,
      zonesFile: Shapefile,
      problems: MutableList<String>,
  ): Map<String, NewPlantingZoneModel> {
    if (zonesFile.features.isEmpty()) {
      throw IllegalArgumentException("No planting zones defined")
    }

    val zoneNames = zonesFile.features.map { it.getProperty(zoneNameProperties)!! }

    zoneNames
        .groupBy { it.lowercase() }
        .values
        .filter { it.size > 1 }
        .forEach {
          throw IllegalArgumentException("Planting zone ${it[0]} appears ${it.size} times")
        }

    zonesFile.features.forEach { feature ->
      checkCoveredBy(feature.geometry, siteFeature.geometry, problems) { percent ->
        val zoneName = feature.getProperty(zoneNameProperties)

        "$percent of planting zone $zoneName is not contained within planting site"
      }
    }

    checkOverlap(zonesFile.features, problems) { feature, otherFeature, overlapPercent ->
      val featureName = feature.getProperty(zoneNameProperties)
      val otherName = otherFeature.getProperty(zoneNameProperties)

      "$overlapPercent of zone $featureName overlaps with zone $otherName"
    }

    return zonesFile.features.associate { feature ->
      val name = feature.getProperty(zoneNameProperties)!!
      val boundary =
          when (val geometry = feature.geometry) {
            is MultiPolygon -> {
              geometry
            }
            is Polygon -> {
              geometry.toMultiPolygon()
            }
            else -> {
              throw IllegalArgumentException(
                  "Planting zone $name geometry must be a MultiPolygon, not a ${geometry.geometryType}")
            }
          }
      val targetPlantingDensity =
          feature.getProperty(targetPlantingDensityProperties)?.toBigDecimalOrNull()
              ?: DEFAULT_TARGET_PLANTING_DENSITY

      val numPermanentClusters =
          feature.getProperty(permanentClusterCountProperties)?.toIntOrNull()
              ?: DEFAULT_NUM_PERMANENT_CLUSTERS
      val numTemporaryPlots =
          feature.getProperty(temporaryPlotCountProperies)?.toIntOrNull()
              ?: DEFAULT_NUM_TEMPORARY_PLOTS

      name to
          NewPlantingZoneModel(
              areaHa = boundary.calculateAreaHectares(feature.coordinateReferenceSystem),
              boundary = boundary,
              errorMargin = DEFAULT_ERROR_MARGIN,
              extraPermanentClusters = 0,
              id = null,
              name = name,
              numPermanentClusters = numPermanentClusters,
              numTemporaryPlots = numTemporaryPlots,
              plantingSubzones = emptyList(),
              studentsT = DEFAULT_STUDENTS_T,
              targetPlantingDensity = targetPlantingDensity,
              variance = DEFAULT_VARIANCE,
          )
    }
  }

  private fun getSubzonesByZone(
      zones: Map<String, PlantingZoneModel<*, *>>,
      subzonesFile: Shapefile,
      problems: MutableList<String>,
  ): Map<String, List<NewPlantingSubzoneModel>> {
    val validSubzones =
        subzonesFile.features.filter { feature ->
          val subzoneName = feature.getProperty(subzoneNameProperties)
          val zoneName = feature.getProperty(zoneNameProperties)

          if (subzoneName == null || subzoneName == "") {
            problems +=
                "Subzone is missing subzone name properties: " +
                    subzoneNameProperties.joinToString()
            false
          } else if (zoneName == null || zoneName == "") {
            problems +=
                "Subzone $subzoneName is missing zone name properties: " +
                    zoneNameProperties.joinToString()
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
          val zoneName = feature.getProperty(zoneNameProperties)!!

          checkCoveredBy(feature.geometry, zones[zoneName]!!.boundary, problems) { percent ->
            val subzoneName = feature.getProperty(subzoneNameProperties)!!

            "$percent of subzone $subzoneName is not contained within zone $zoneName"
          }

          zoneName
        }

    checkOverlap(validSubzones, problems) { feature, otherFeature, overlapPercent ->
      val featureName = feature.getProperty(subzoneNameProperties)
      val otherName = otherFeature.getProperty(subzoneNameProperties)

      "$overlapPercent of subzone $featureName overlaps with subzone $otherName"
    }

    zones.keys.forEach { zoneName ->
      if (zoneName !in subzonesByZone) {
        problems += "Zone $zoneName has no subzones"
      }
    }

    return subzonesByZone.mapValues { (zoneName, subzoneFeatures) ->
      subzoneFeatures.map { subzoneFeature ->
        val boundary = subzoneFeature.geometry
        val name = subzoneFeature.getProperty(subzoneNameProperties)!!

        NewPlantingSubzoneModel(
            areaHa = boundary.calculateAreaHectares(subzoneFeature.coordinateReferenceSystem),
            boundary = boundary.toMultiPolygon(),
            id = null,
            fullName = "$zoneName-$name",
            name = name,
        )
      }
    }
  }

  private fun checkCoveredBy(
      child: Geometry,
      parent: Geometry,
      problems: MutableList<String>,
      problemFunc: (String) -> String
  ) {
    if (!child.coveredBy(parent)) {
      val difference = child.difference(parent)
      val childArea = child.area
      val differenceArea = difference.area
      val uncoveredPercent = differenceArea / childArea * 100.0

      if (uncoveredPercent > OUTSIDE_BOUNDS_MIN_PERCENT) {
        val problem = problemFunc("%.02f%%".format(uncoveredPercent))
        problems += problem

        log.debug(problem)
        log.debug("Parent: $parent")
        log.debug("Child: $child")
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

  private fun getFullSubzoneName(feature: ShapefileFeature): String =
      "${feature.getProperty(zoneNameProperties)!!}-${feature.getProperty(subzoneNameProperties)!!}"

  private fun geometryFactory(feature: ShapefileFeature) =
      GeometryFactory(PrecisionModel(), feature.geometry.srid)
}
