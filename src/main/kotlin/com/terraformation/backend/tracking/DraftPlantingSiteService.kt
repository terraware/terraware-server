package com.terraformation.backend.tracking

import com.terraformation.backend.gis.GeometryFileParser
import com.terraformation.backend.gis.InvalidGeometryException
import com.terraformation.backend.gis.NoPolygonsException
import com.terraformation.backend.gis.TooManyVerticesException
import com.terraformation.backend.gis.UnsupportedGeometryFileFormatException
import com.terraformation.backend.gis.convertToXY
import com.terraformation.backend.gis.extractPolygons
import com.terraformation.backend.gis.mergeToMultiPolygon
import com.terraformation.backend.tracking.model.BoundaryFileModel
import com.terraformation.backend.util.calculateAreaHectares
import jakarta.inject.Named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.TopologyException

@Named
class DraftPlantingSiteService(private val geometryFileParser: GeometryFileParser) {
  companion object {
    private const val MAX_BOUNDARY_VERTICES = 50000
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

  /**
   * Combines the polygons from an uploaded file into a single boundary. Non-polygonal shapes are
   * rejected, as are invalid polygons; geometries are not repaired.
   */
  private fun combineBoundary(geometries: List<Geometry>): MultiPolygon {
    val polygons =
        geometries
            .flatMap { geometry ->
              geometry.extractPolygons {
                throw InvalidGeometryException()
              }
            }
            .filterNot { it.isEmpty }
            .map { convertToXY(it, precisionModel = null) }

    if (polygons.isEmpty()) {
      throw NoPolygonsException()
    }
    if (polygons.any { !it.isValid }) {
      throw InvalidGeometryException()
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
