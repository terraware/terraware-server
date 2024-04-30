package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.model.NewPlantingSubzoneModel
import com.terraformation.backend.tracking.model.NewPlantingZoneModel
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.tracking.model.ShapefileFeature
import com.terraformation.backend.util.toMultiPolygon
import jakarta.inject.Named
import java.math.BigDecimal
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

@Named
class PlantingSiteImporter(
    private val plantingSiteStore: PlantingSiteStore,
) {
  companion object {
    val siteNameProperties = setOf("planting_s", "site")
    val subzoneNameProperties = setOf("planting_1", "subzone")
    val targetPlantingDensityProperties = setOf("plan_dens", "density")
    val zoneNameProperties = setOf("planting_z", "zone")

    // Optional zone-level properties to set initial plot counts; mostly for testing
    val permanentClusterCountProperties = setOf("permanent")
    val temporaryPlotCountProperies = setOf("temporary")

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
      throw PlantingSiteMapInvalidException(
          "Expected 3 or 4 shapefiles (site, zones, subzones, and optionally exclusions) but " +
              "found ${shapefiles.size}")
    }

    var siteFile: Shapefile? = null
    var zonesFile: Shapefile? = null
    var subzonesFile: Shapefile? = null
    var exclusionsFile: Shapefile? = null

    shapefiles.forEach { shapefile ->
      if (shapefile.features.isEmpty()) {
        throw PlantingSiteMapInvalidException("Shapefiles must contain geometries")
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
            ?: throw PlantingSiteMapInvalidException(
                "Planting site shapefile must contain exactly one geometry and one of these properties: " +
                    siteNameProperties.joinToString()),
        zonesFile
            ?: throw PlantingSiteMapInvalidException(
                "Planting zones shapefile features must include one of these properties: " +
                    zoneNameProperties.joinToString()),
        subzonesFile
            ?: throw PlantingSiteMapInvalidException(
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
    val zonesByName = getZones(zonesFile)
    val subzonesByZone = getSubzonesByZone(zonesByName, subzonesFile, problems)
    val exclusion = getExclusion(exclusionsFile, problems)

    if (problems.isNotEmpty()) {
      throw PlantingSiteMapInvalidException(problems)
    }

    return plantingSiteStore
        .createPlantingSite(
            PlantingSiteModel.create(
                boundary = siteFeature.geometry.toMultiPolygon(),
                description = description,
                exclusion = exclusion,
                name = name,
                organizationId = organizationId,
                plantingZones =
                    zonesByName.values.map { zone ->
                      zone.copy(plantingSubzones = subzonesByZone[zone.name] ?: emptyList())
                    }))
        .id
  }

  private fun getSiteBoundary(
      siteFile: Shapefile,
      problems: MutableList<String>
  ): ShapefileFeature {
    if (siteFile.features.isEmpty()) {
      throw PlantingSiteMapInvalidException("Planting site shapefile does not contain a boundary")
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
                  throw PlantingSiteMapInvalidException(problems)
                }
              }
            }

    return GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
        .createMultiPolygon(allPolygons.toTypedArray())
  }

  private fun getZones(
      zonesFile: Shapefile,
  ): Map<String, NewPlantingZoneModel> {
    if (zonesFile.features.isEmpty()) {
      throw IllegalArgumentException("No planting zones defined")
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
              ?: PlantingZoneModel.DEFAULT_TARGET_PLANTING_DENSITY

      val numPermanentClusters =
          feature.getProperty(permanentClusterCountProperties)?.toIntOrNull()
              ?: PlantingZoneModel.DEFAULT_NUM_PERMANENT_CLUSTERS
      val numTemporaryPlots =
          feature.getProperty(temporaryPlotCountProperies)?.toIntOrNull()
              ?: PlantingZoneModel.DEFAULT_NUM_TEMPORARY_PLOTS

      name to
          PlantingZoneModel.create(
              areaHa = BigDecimal.ZERO, // Will be calculated when site is created
              boundary = boundary,
              name = name,
              numPermanentClusters = numPermanentClusters,
              numTemporaryPlots = numTemporaryPlots,
              plantingSubzones = emptyList(),
              targetPlantingDensity = targetPlantingDensity,
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
        validSubzones.groupBy { feature -> feature.getProperty(zoneNameProperties)!! }

    return subzonesByZone.mapValues { (zoneName, subzoneFeatures) ->
      subzoneFeatures.map { subzoneFeature ->
        val boundary = subzoneFeature.geometry
        val name = subzoneFeature.getProperty(subzoneNameProperties)!!

        PlantingSubzoneModel.create(
            areaHa = BigDecimal.ZERO, // Will be calculated when site is created
            boundary = boundary.toMultiPolygon(),
            fullName = "$zoneName-$name",
            name = name,
        )
      }
    }
  }
}
