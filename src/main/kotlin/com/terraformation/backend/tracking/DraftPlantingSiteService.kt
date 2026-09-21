package com.terraformation.backend.tracking

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.file.useAndDelete
import com.terraformation.backend.gis.GeometryFileParser
import com.terraformation.backend.tracking.model.Shapefile
import jakarta.inject.Named
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes
import org.geotools.util.ContentFormatException
import org.locationtech.jts.geom.Geometry
import org.xml.sax.SAXException

@Named
class DraftPlantingSiteService(private val geometryFileParser: GeometryFileParser) {
  fun uploadBoundaryFile(
      draftPlantingSiteId: DraftPlantingSiteId,
      content: ByteArray,
      filename: String?,
  ): List<Geometry> {
    requirePermissions { updateDraftPlantingSite(draftPlantingSiteId) }

    return try {
      when (filename?.substringAfterLast('.', "")?.lowercase()) {
        "kml",
        "kmz",
        "geojson",
        "json" -> listOf(geometryFileParser.parse(content, filename))
        "zip" -> parseShapefile(content)
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

  private fun parseShapefile(content: ByteArray): List<Geometry> {
    return createTempFile(suffix = ".zip").useAndDelete { path ->
      path.writeBytes(content)

      ZipFile(path.toFile()).use { zip ->
        val filenames =
            zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        val shapefiles = filenames.filter { it.endsWith(".shp", ignoreCase = true) }
        if (shapefiles.size != 1) {
          throw ContentFormatException("Archive must contain exactly one .shp file")
        }

        val basename = shapefiles.single().substringBeforeLast('.')
        for (extension in listOf("shx", "dbf", "prj")) {
          if (filenames.none { it.equals("$basename.$extension", ignoreCase = true) }) {
            throw ContentFormatException("Archive is missing $basename.$extension")
          }
        }
      }

      Shapefile.fromZipFile(path).single().features.map { it.geometry }
    }
  }
}
