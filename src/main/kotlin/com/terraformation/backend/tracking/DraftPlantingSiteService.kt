package com.terraformation.backend.tracking

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.gis.GeometryFileParser
import jakarta.inject.Named
import java.io.IOException
import org.geotools.util.ContentFormatException
import org.locationtech.jts.geom.Geometry
import org.xml.sax.SAXException

@Named
class DraftPlantingSiteService(private val geometryFileParser: GeometryFileParser) {
  fun parseBoundaryFile(
      draftPlantingSiteId: DraftPlantingSiteId,
      content: ByteArray,
      filename: String?,
  ): Geometry {
    requirePermissions { updateDraftPlantingSite(draftPlantingSiteId) }

    return try {
      when (filename?.substringAfterLast('.', "")?.lowercase()) {
        "kml",
        "kmz",
        "geojson",
        "json",
        "zip" -> geometryFileParser.parse(content, filename)
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
