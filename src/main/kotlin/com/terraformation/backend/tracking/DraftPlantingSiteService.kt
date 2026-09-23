package com.terraformation.backend.tracking

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.gis.GeometryFileErrorCode
import com.terraformation.backend.gis.GeometryFileException
import com.terraformation.backend.gis.GeometryFileParser
import com.terraformation.backend.tracking.model.BoundaryFileModel
import com.terraformation.backend.util.calculateAreaHectares
import jakarta.inject.Named
import java.io.IOException
import org.geotools.util.ContentFormatException
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.PolygonExtracter
import org.xml.sax.SAXException

@Named
class DraftPlantingSiteService(private val geometryFileParser: GeometryFileParser) {
  companion object {
    private const val MAX_BOUNDARY_VERTICES = 50000
    private val SUPPORTED_EXTENSIONS = setOf("kml", "kmz", "geojson", "json", "zip")
  }

  fun parseBoundaryFile(
      draftPlantingSiteId: DraftPlantingSiteId,
      content: ByteArray,
      filename: String?,
  ): BoundaryFileModel {
    requirePermissions { updateDraftPlantingSite(draftPlantingSiteId) }

    if (filename?.substringAfterLast('.', "")?.lowercase() !in SUPPORTED_EXTENSIONS) {
      throw GeometryFileException(GeometryFileErrorCode.UnsupportedFormat)
    }

    return try {
      when (filename?.substringAfterLast('.', "")?.lowercase()) {
        "kml",
        "kmz",
        "geojson",
        "json",
        "zip" -> {
          val parsed = geometryFileParser.parseWithFormat(content, filename)
          val polygonArray =
              PolygonExtracter.getPolygons(parsed.geometry)
                  .filterIsInstance<Polygon>()
                  .filterNot { it.isEmpty }
                  .toTypedArray()
          val polygons =
              parsed.geometry.factory.createMultiPolygon(polygonArray).also {
                it.srid = parsed.geometry.srid
              }
          if (polygons.numPoints > MAX_BOUNDARY_VERTICES) {
            throw GeometryFileException(GeometryFileErrorCode.TooManyVertices)
          }

          BoundaryFileModel(
              areaHa = polygons.calculateAreaHectares(),
              filename = filename,
              format = parsed.format,
              geometry = parsed.geometry,
              numPolygons = polygons.numGeometries,
          )
        }
        else ->
            throw ContentFormatException(
                "Boundary file must be .kml, .kmz, .geojson, .json, or .zip"
            )
      }
    } catch (e: IOException) {
      throw ContentFormatException("Unable to read boundary file: ${e.message}")
    } catch (e: SAXException) {
      throw ContentFormatException("Unable to parse boundary XML: ${e.message}")
    } catch (e: IllegalArgumentException) {
      throw ContentFormatException("Invalid boundary file: ${e.message}")
    }
  }
}
