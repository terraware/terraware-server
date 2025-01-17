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
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.CoordinateXY
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.geom.util.GeometryEditor
import org.locationtech.jts.operation.overlay.snap.GeometrySnapper
import org.locationtech.jts.precision.GeometryPrecisionReducer

@Named
class PlantingSiteImporter(
    private val plantingSiteStore: PlantingSiteStore,
) {
  companion object {
    val subzoneNameProperties = setOf("planting_1", "subzone")
    val targetPlantingDensityProperties = setOf("plan_dens", "density")
    val zoneNameProperties = setOf("planting_z", "zone")

    // Zone-level properties to control number of monitoring plots
    val errorMarginProperties = setOf("error_marg")
    val studentsTProperties = setOf("students_t")
    val varianceProperties = setOf("variance")

    // Optional zone-level properties to set initial plot counts; mostly for testing
    val permanentClusterCountProperties = setOf("permanent")
    val temporaryPlotCountProperties = setOf("temporary")
  }

  /**
   * When importing, use fixed-precision coordinates with 8 decimal digits rather than the default
   * floating-point precision. This helps avoid introducing slight errors to calculated geometries
   * such as when zone boundaries are derived from subzone boundaries.
   *
   * 8 decimal digits works out to a resolution of less than 1 centimeter.
   */
  private val precisionReducer = GeometryPrecisionReducer(PrecisionModel(100000000.0))

  fun import(
      name: String,
      description: String? = null,
      organizationId: OrganizationId,
      shapefiles: Collection<Shapefile>,
      gridOrigin: Point? = null,
  ): PlantingSiteId {
    requirePermissions { createPlantingSite(organizationId) }

    val newModel = shapefilesToModel(shapefiles, name, description, organizationId, gridOrigin)

    return plantingSiteStore.createPlantingSite(newModel).id
  }

  fun shapefilesToModel(
      shapefiles: Collection<Shapefile>,
      name: String,
      description: String?,
      organizationId: OrganizationId,
      gridOrigin: Point? = null,
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
        exclusionsFile,
        gridOrigin,
    )
  }

  private fun shapefilesToModel(
      name: String,
      description: String?,
      organizationId: OrganizationId,
      subzonesFile: Shapefile,
      exclusionsFile: Shapefile?,
      gridOrigin: Point? = null,
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
            gridOrigin = gridOrigin,
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
            .map { convertToXY(it.geometry) }
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

          if (subzoneName.isNullOrBlank()) {
            problems +=
                "Subzone is missing subzone name properties: " +
                    subzoneNameProperties.joinToString()
            false
          } else if (zoneName.isNullOrBlank()) {
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

    return subzonesByZone.mapNotNull { (zoneName, subzoneFeatures) ->
      val subzoneModels =
          subzoneFeatures.map { subzoneFeature ->
            val boundary = convertToXY(subzoneFeature.geometry)
            val name = subzoneFeature.getProperty(subzoneNameProperties)!!

            PlantingSubzoneModel.create(
                boundary = boundary.toMultiPolygon(),
                exclusion = exclusion,
                fullName = "$zoneName-$name",
                name = name,
            )
          }

      val zoneBoundary = mergeToMultiPolygon(subzoneModels.map { it.boundary })

      // Zone settings only need to appear on one subzone; take the first valid values we find.
      val errorMargin =
          subzoneFeatures
              .mapNotNull { it.getProperty(errorMarginProperties)?.toBigDecimalOrNull() }
              .find { it.signum() > 0 }
      val variance =
          subzoneFeatures
              .mapNotNull { it.getProperty(varianceProperties)?.toBigDecimalOrNull() }
              .find { it.signum() > 0 }
      val studentsT =
          subzoneFeatures
              .mapNotNull { it.getProperty(studentsTProperties)?.toBigDecimalOrNull() }
              .find { it.signum() > 0 } ?: PlantingZoneModel.DEFAULT_STUDENTS_T

      val numPermanentClusters =
          subzoneFeatures.firstNotNullOfOrNull {
            it.getProperty(permanentClusterCountProperties)?.toIntOrNull()
          }
      val numTemporaryPlots =
          subzoneFeatures.firstNotNullOfOrNull {
            it.getProperty(temporaryPlotCountProperties)?.toIntOrNull()
          }
      val targetPlantingDensity =
          subzoneFeatures.firstNotNullOfOrNull {
            it.getProperty(targetPlantingDensityProperties)?.toBigDecimalOrNull()
          } ?: PlantingZoneModel.DEFAULT_TARGET_PLANTING_DENSITY

      if (errorMargin != null && variance != null) {
        PlantingZoneModel.create(
            boundary = zoneBoundary,
            errorMargin = errorMargin,
            exclusion = exclusion,
            name = zoneName,
            numPermanentClusters = numPermanentClusters,
            numTemporaryPlots = numTemporaryPlots,
            plantingSubzones = subzoneModels,
            studentsT = studentsT,
            targetPlantingDensity = targetPlantingDensity,
            variance = variance,
        )
      } else {
        if (errorMargin == null) {
          problems +=
              "Zone $zoneName has no subzone with positive value for properties: " +
                  errorMarginProperties.joinToString()
        }
        if (variance == null) {
          problems +=
              "Zone $zoneName has no subzone with positive value for properties: " +
                  varianceProperties.joinToString()
        }

        null
      }
    }
  }

  /**
   * Merges a set of geometries into a MultiPolygon. If two geometries are adjacent but have a tiny
   * gap, e.g., due to floating-point precision limitations, the gap is eliminated.
   */
  private fun mergeToMultiPolygon(geometries: Collection<Geometry>): MultiPolygon {
    return geometries
        .reduce { a, b ->
          val tolerance = GeometrySnapper.computeOverlaySnapTolerance(a, b)
          a.union(GeometrySnapper(b).snapTo(a, tolerance))
        }
        .toMultiPolygon()
  }

  /**
   * Converts a geometry's coordinates to XY, stripping the Z and M dimensions if present and using
   * fixed-precision coordinates for X and Y.
   */
  private fun convertToXY(geometry: Geometry): Geometry {
    return precisionReducer.reduce(
        GeometryEditor(geometry.factory).edit(geometry, XYEditorOperation))
  }

  /** Geometry editor operation that converts XYZ or XYZM coordinates to XY ones. */
  object XYEditorOperation : GeometryEditor.CoordinateOperation() {
    override fun edit(coordinates: Array<out Coordinate>, geometry: Geometry): Array<Coordinate> {
      return coordinates.map { it as? CoordinateXY ?: CoordinateXY(it.x, it.y) }.toTypedArray()
    }
  }
}
