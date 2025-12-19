package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.tracking.model.NewPlantingSiteModel
import com.terraformation.backend.tracking.model.NewStratumModel
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.tracking.model.StratumModel
import com.terraformation.backend.tracking.model.SubstratumModel
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
    val substratumNameProperties = setOf("planting_1", "subzone", "substratum", "substrata")
    val substratumStableIdProperties = setOf("stable_sz", "stable_sub", "stable_ss")
    val targetPlantingDensityProperties = setOf("plan_dens", "density")
    val stratumNameProperties = setOf("planting_z", "zone", "stratum", "strata")
    val stratumStableIdProperties = setOf("stable_z", "stable_zon", "stable_s", "stable_str")

    // Stratum-level properties to control number of monitoring plots
    val errorMarginProperties = setOf("error_marg")
    val studentsTProperties = setOf("students_t")
    val varianceProperties = setOf("variance")

    // Optional stratum-level properties to set initial plot counts; mostly for testing
    val permanentPlotCountProperties = setOf("permanent")
    val temporaryPlotCountProperties = setOf("temporary")
  }

  /**
   * When importing, use fixed-precision coordinates with 8 decimal digits rather than the default
   * floating-point precision. This helps avoid introducing slight errors to calculated geometries
   * such as when stratum boundaries are derived from substratum boundaries.
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
      requireStableIds: Boolean = false,
  ): NewPlantingSiteModel {
    if (shapefiles.isEmpty() || shapefiles.size > 2) {
      throw ShapefilesInvalidException(
          "Expected substrata and optionally exclusions but found ${shapefiles.size} shapefiles"
      )
    }

    var substrataFile: Shapefile? = null
    var exclusionsFile: Shapefile? = null

    shapefiles.forEach { shapefile ->
      if (shapefile.features.isEmpty()) {
        throw ShapefilesInvalidException("Shapefiles must contain geometries")
      }

      if (shapefile.features.all { it.hasProperty(substratumNameProperties) }) {
        substrataFile = shapefile
      } else {
        exclusionsFile = shapefile
      }
    }

    return shapefilesToModel(
        name,
        description,
        organizationId,
        substrataFile
            ?: throw ShapefilesInvalidException(
                "Substrata shapefile features must include one of these properties: " +
                    substratumNameProperties.joinToString()
            ),
        exclusionsFile,
        gridOrigin,
        requireStableIds,
    )
  }

  private fun shapefilesToModel(
      name: String,
      description: String?,
      organizationId: OrganizationId,
      substrataFile: Shapefile,
      exclusionsFile: Shapefile?,
      gridOrigin: Point? = null,
      requireStableIds: Boolean = false,
  ): NewPlantingSiteModel {
    val problems = mutableListOf<String>()

    val exclusion = getExclusion(exclusionsFile, problems)
    val strataWithSubstrata =
        getStrataWithSubstrata(substrataFile, exclusion, problems, requireStableIds)

    if (problems.isNotEmpty()) {
      throw ShapefilesInvalidException(problems)
    }

    val siteBoundary = mergeToMultiPolygon(strataWithSubstrata.map { it.boundary })

    val newModel =
        PlantingSiteModel.create(
            boundary = siteBoundary,
            description = description,
            exclusion = exclusion,
            gridOrigin = gridOrigin,
            name = name,
            organizationId = organizationId,
            strata = strataWithSubstrata,
        )
    return newModel
  }

  /**
   * Returns a single MultiPolygon that combines all the Polygons and MultiPolygons in the
   * exclusions shapefile.
   */
  private fun getExclusion(
      exclusionsFile: Shapefile?,
      problems: MutableList<String>,
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

  private fun getStrataWithSubstrata(
      substrataFile: Shapefile,
      exclusion: MultiPolygon?,
      problems: MutableList<String>,
      requireStableIds: Boolean,
  ): List<NewStratumModel> {
    val validSubstrata =
        substrataFile.features.filter { feature ->
          fun checkProperty(
              description: String,
              properties: Set<String>,
              require: Boolean = true,
          ): String? {
            return if (require && feature.getProperty(properties).isNullOrBlank()) {
              val substratumName = feature.getProperty(substratumNameProperties) ?: "<no name>"

              "Substratum $substratumName is missing $description properties: " +
                  properties.joinToString()
            } else {
              null
            }
          }

          val featureProblems =
              listOfNotNull(
                  checkProperty("substratum name", substratumNameProperties),
                  checkProperty("stratum name", stratumNameProperties),
                  checkProperty(
                      "substratum stable ID",
                      substratumStableIdProperties,
                      requireStableIds,
                  ),
                  checkProperty("stratum stable ID", stratumStableIdProperties, requireStableIds),
              )

          problems += featureProblems
          featureProblems.isEmpty()
        }

    validSubstrata
        .groupBy { substratum ->
          substratum.getProperty(substratumStableIdProperties)
              ?: (substratum.getProperty(stratumNameProperties) +
                  "-" +
                  substratum.getProperty(substratumNameProperties))
        }
        .forEach { (stableId, substrata) ->
          if (substrata.size > 1) {
            val substratumNames =
                substrata.joinToString(", ") { it.getProperty(substratumNameProperties)!! }
            problems += "Duplicate stable ID $stableId on substrata: $substratumNames"
          }
        }

    val stableIdsByStratum = mutableMapOf<String, StableId>()
    val strataByStableId = mutableMapOf<StableId, String>()

    validSubstrata.forEach { feature ->
      val stratumName = feature.getProperty(stratumNameProperties)!!
      val stableId = StableId(feature.getProperty(stratumStableIdProperties) ?: stratumName)
      val existingStratumName = strataByStableId[stableId]
      val existingStableId = stableIdsByStratum[stratumName]

      if (existingStableId != null) {
        if (existingStableId != stableId) {
          problems +=
              "Inconsistent stable IDs for stratum $stratumName: $existingStableId, $stableId"
        }
      } else {
        stableIdsByStratum[stratumName] = stableId
      }

      if (existingStratumName != null) {
        if (existingStratumName != stratumName) {
          problems +=
              "Inconsistent stratum names for stable ID $stableId: $existingStratumName, $stratumName"
        }
      } else {
        strataByStableId[stableId] = stratumName
      }
    }

    val substrataByStratum =
        validSubstrata.groupBy { feature -> feature.getProperty(stratumNameProperties)!! }

    return substrataByStratum.mapNotNull { (stratumName, substratumFeatures) ->
      val substratumModels =
          substratumFeatures.map { substratumFeature ->
            val boundary = convertToXY(substratumFeature.geometry)
            val name = substratumFeature.getProperty(substratumNameProperties)!!
            val fullName = "$stratumName-$name"
            val stableId =
                StableId(substratumFeature.getProperty(substratumStableIdProperties) ?: fullName)

            SubstratumModel.create(
                boundary = boundary.toMultiPolygon(),
                exclusion = exclusion,
                fullName = fullName,
                name = name,
                stableId = stableId,
            )
          }

      val stratumBoundary = mergeToMultiPolygon(substratumModels.map { it.boundary })

      // Stratum settings only need to appear on one substratum; take the first valid values we
      // find.
      val errorMargin =
          substratumFeatures
              .mapNotNull { it.getProperty(errorMarginProperties)?.toBigDecimalOrNull() }
              .find { it.signum() > 0 }
      val variance =
          substratumFeatures
              .mapNotNull { it.getProperty(varianceProperties)?.toBigDecimalOrNull() }
              .find { it.signum() > 0 }
      val studentsT =
          substratumFeatures
              .mapNotNull { it.getProperty(studentsTProperties)?.toBigDecimalOrNull() }
              .find { it.signum() > 0 } ?: StratumModel.DEFAULT_STUDENTS_T

      val numPermanentPlots =
          substratumFeatures.firstNotNullOfOrNull {
            it.getProperty(permanentPlotCountProperties)?.toIntOrNull()
          }
      val numTemporaryPlots =
          substratumFeatures.firstNotNullOfOrNull {
            it.getProperty(temporaryPlotCountProperties)?.toIntOrNull()
          }
      val targetPlantingDensity =
          substratumFeatures.firstNotNullOfOrNull {
            it.getProperty(targetPlantingDensityProperties)?.toBigDecimalOrNull()
          } ?: StratumModel.DEFAULT_TARGET_PLANTING_DENSITY

      if (errorMargin != null && variance != null) {
        StratumModel.create(
            boundary = stratumBoundary,
            errorMargin = errorMargin,
            exclusion = exclusion,
            name = stratumName,
            numPermanentPlots = numPermanentPlots,
            numTemporaryPlots = numTemporaryPlots,
            substrata = substratumModels,
            stableId = stableIdsByStratum[stratumName]!!,
            studentsT = studentsT,
            targetPlantingDensity = targetPlantingDensity,
            variance = variance,
        )
      } else {
        if (errorMargin == null) {
          problems +=
              "Stratum $stratumName has no substratum with positive value for properties: " +
                  errorMarginProperties.joinToString()
        }
        if (variance == null) {
          problems +=
              "Stratum $stratumName has no substratum with positive value for properties: " +
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
        GeometryEditor(geometry.factory).edit(geometry, XYEditorOperation)
    )
  }

  /** Geometry editor operation that converts XYZ or XYZM coordinates to XY ones. */
  object XYEditorOperation : GeometryEditor.CoordinateOperation() {
    override fun edit(coordinates: Array<out Coordinate>, geometry: Geometry): Array<Coordinate> {
      return coordinates.map { it as? CoordinateXY ?: CoordinateXY(it.x, it.y) }.toTypedArray()
    }
  }
}
