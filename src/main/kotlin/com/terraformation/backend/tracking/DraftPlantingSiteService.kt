package com.terraformation.backend.tracking

import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.gis.GeometryFileParser
import com.terraformation.backend.gis.InvalidGeometryException
import com.terraformation.backend.gis.NoPolygonsException
import com.terraformation.backend.gis.TooManyVerticesException
import com.terraformation.backend.gis.UnsupportedGeometryFileFormatException
import com.terraformation.backend.gis.convertToXY
import com.terraformation.backend.gis.extractPolygons
import com.terraformation.backend.gis.mergeToMultiPolygon
import com.terraformation.backend.tracking.db.DraftPlantingSiteStore
import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import com.terraformation.backend.tracking.model.BoundaryFileModel
import com.terraformation.backend.tracking.model.DraftPlantingSiteModel
import com.terraformation.backend.util.calculateAreaHectares
import jakarta.inject.Named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.TopologyException

@Named
class DraftPlantingSiteService(
    private val draftPlantingSiteStore: DraftPlantingSiteStore,
    private val geometryFileParser: GeometryFileParser,
) {
  companion object {
    private const val MAX_BOUNDARY_VERTICES = 50000
  }

  /**
   * Validates a draft's map and, if it is valid, stores the draft.
   *
   * @throws PlantingSiteMapInvalidException The map failed validation. Nothing is stored.
   */
  fun createTyped(model: DraftPlantingSiteModel): DraftPlantingSiteModel {
    model.requireValidMap()

    return draftPlantingSiteStore.create(model)
  }

  /**
   * Updates a draft and validates its updated map. Validation runs inside the store's transaction
   * before anything is written.
   *
   * @throws PlantingSiteMapInvalidException The updated map failed validation. Nothing is written.
   */
  fun updateTyped(
      id: DraftPlantingSiteId,
      buildModel: (DraftPlantingSiteModel) -> DraftPlantingSiteModel,
  ) {
    draftPlantingSiteStore.update(id) { existing ->
      buildModel(existing).also { it.requireValidMap() }
    }
  }

  /**
   * Turns an uploaded file into a planting site boundary. This does not modify the draft; clients
   * decide what to do with the result.
   */
  fun parseBoundaryFile(
      content: ByteArray,
      filename: String?,
  ): BoundaryFileModel {
    if (!GeometryFileParser.hasSupportedExtension(filename)) {
      throw UnsupportedGeometryFileFormatException()
    }

    val parsed = geometryFileParser.readWithFormat(content, filename)
    val boundary = combineBoundary(parsed.geometries)

    return BoundaryFileModel(
        areaHa = boundary.calculateAreaHectares(),
        filename = filename ?: "",
        format = parsed.format,
        geometry = boundary,
        numPolygons = boundary.numGeometries,
    )
  }

  private fun DraftPlantingSiteModel.requireValidMap() {
    validate()?.let { throw PlantingSiteMapInvalidException(it) }
  }

  /**
   * Combines the polygons from an uploaded file into a single boundary. Non-polygonal shapes such
   * as points and lines are ignored. Files with no polygons are rejected, as are invalid polygons;
   * geometries are not repaired.
   */
  private fun combineBoundary(geometries: List<Geometry>): MultiPolygon {
    val polygons =
        geometries
            .filter { it is Polygon || it is MultiPolygon }
            .flatMap { geometry ->
              val polygons = geometry.extractPolygons { throw InvalidGeometryException() }
              if (!geometry.isValid) {
                throw InvalidGeometryException()
              }
              polygons
            }
            .filterNot { it.isEmpty }
            .map { it.convertToXY(precisionModel = null) }

    if (polygons.isEmpty()) {
      throw NoPolygonsException()
    }

    val boundary =
        try {
          runBlocking(Dispatchers.Default) { mergeToMultiPolygon(polygons) }
        } catch (e: TopologyException) {
          throw InvalidGeometryException(e)
        }

    if (boundary.isEmpty) {
      throw NoPolygonsException()
    }
    if (!boundary.isValid) {
      throw InvalidGeometryException()
    }
    if (boundary.numPoints > MAX_BOUNDARY_VERTICES) {
      throw TooManyVerticesException()
    }

    return boundary
  }
}
