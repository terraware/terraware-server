package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.tracking.model.NewPlantingSiteModel
import com.terraformation.backend.tracking.model.NewPlantingZoneModel
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.util.toMultiPolygon
import jakarta.inject.Named
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon

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
    val temporaryPlotCountProperties = setOf("temporary")
  }

  fun import(
      name: String,
      description: String? = null,
      organizationId: OrganizationId,
      shapefiles: Collection<Shapefile>,
  ): PlantingSiteId {
    requirePermissions { createPlantingSite(organizationId) }

    val newModel = shapefilesToModel(shapefiles, name, description, organizationId)

    return plantingSiteStore.createPlantingSite(newModel).id
  }

  fun shapefilesToModel(
      shapefiles: Collection<Shapefile>,
      name: String,
      description: String?,
      organizationId: OrganizationId
  ): NewPlantingSiteModel {
    if (shapefiles.isEmpty() || shapefiles.size > 2) {
      throw ShapefilesInvalidException(
          "Expected subzones and optionally exclusions but found ${shapefiles.size} shapefiles")
    }

    var subzonesFile: Shapefile? = null
    var exclusionsFile: Shapefile? = null

    shapefiles.forEach { shapefile ->
      if (shapefile.features.isEmpty()) {
        throw ShapefilesInvalidException("Shapefiles must contain geometries")
      }

      if (shapefile.features.all { it.hasProperty(subzoneNameProperties) }) {
        subzonesFile = shapefile
      } else {
        exclusionsFile = shapefile
      }
    }

    return shapefilesToModel(
        name,
        description,
        organizationId,
        subzonesFile
            ?: throw ShapefilesInvalidException(
                "Subzones shapefile features must include one of these properties: " +
                    subzoneNameProperties.joinToString()),
        exclusionsFile)
  }

  private fun shapefilesToModel(
      name: String,
      description: String?,
      organizationId: OrganizationId,
      subzonesFile: Shapefile,
      exclusionsFile: Shapefile?
  ): NewPlantingSiteModel {
    val problems = mutableListOf<String>()

    val exclusion = getExclusion(exclusionsFile, problems)
    val zonesWithSubzones = getZonesWithSubzones(subzonesFile, exclusion, problems)

    if (problems.isNotEmpty()) {
      throw ShapefilesInvalidException(problems)
    }

    val siteBoundary = mergeToMultiPolygon(zonesWithSubzones.map { it.boundary })

    val newModel =
        PlantingSiteModel.create(
            boundary = siteBoundary,
            description = description,
            exclusion = exclusion,
            name = name,
            organizationId = organizationId,
            plantingZones = zonesWithSubzones,
        )
    return newModel
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
                    (0..<geometry.numGeometries).map { geometry.getGeometryN(it) as Polygon }
                else -> {
                  problems.add("Exclusion geometries must all be Polygon or MultiPolygon.")
                  throw ShapefilesInvalidException(problems)
                }
              }
            }

    return exclusionsFile.features[0]
        .geometry
        .factory
        .createMultiPolygon(allPolygons.toTypedArray())
  }

  private fun getZonesWithSubzones(
      subzonesFile: Shapefile,
      exclusion: MultiPolygon?,
      problems: MutableList<String>,
  ): List<NewPlantingZoneModel> {
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
          } else {
            true
          }
        }

    val subzonesByZone =
        validSubzones.groupBy { feature -> feature.getProperty(zoneNameProperties)!! }

    return subzonesByZone.map { (zoneName, subzoneFeatures) ->
      val subzoneModels =
          subzoneFeatures.map { subzoneFeature ->
            val boundary = subzoneFeature.geometry
            val name = subzoneFeature.getProperty(subzoneNameProperties)!!

            PlantingSubzoneModel.create(
                boundary = boundary.toMultiPolygon(),
                exclusion = exclusion,
                fullName = "$zoneName-$name",
                name = name,
            )
          }

      val zoneBoundary = mergeToMultiPolygon(subzoneModels.map { it.boundary })

      // Zone settings only need to appear on one subzone; take the first values we find.
      val numPermanentClusters =
          subzoneFeatures.firstNotNullOfOrNull {
            it.getProperty(permanentClusterCountProperties)?.toIntOrNull()
          } ?: PlantingZoneModel.DEFAULT_NUM_PERMANENT_CLUSTERS
      val numTemporaryPlots =
          subzoneFeatures.firstNotNullOfOrNull {
            it.getProperty(temporaryPlotCountProperties)?.toIntOrNull()
          } ?: PlantingZoneModel.DEFAULT_NUM_TEMPORARY_PLOTS
      val targetPlantingDensity =
          subzoneFeatures.firstNotNullOfOrNull {
            it.getProperty(targetPlantingDensityProperties)?.toBigDecimalOrNull()
          } ?: PlantingZoneModel.DEFAULT_TARGET_PLANTING_DENSITY

      PlantingZoneModel.create(
          boundary = zoneBoundary,
          exclusion = exclusion,
          name = zoneName,
          numPermanentClusters = numPermanentClusters,
          numTemporaryPlots = numTemporaryPlots,
          plantingSubzones = subzoneModels,
          targetPlantingDensity = targetPlantingDensity,
      )
    }
  }

  private fun mergeToMultiPolygon(geometries: Collection<Geometry>): MultiPolygon =
      geometries.reduce { a, b -> a.union(b) }.toMultiPolygon()
}
