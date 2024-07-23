package com.terraformation.backend.gis

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.db.SRID
import jakarta.inject.Named
import jakarta.ws.rs.core.MediaType
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes
import org.apache.tika.Tika
import org.geotools.api.feature.simple.SimpleFeature
import org.geotools.kml.v22.KMLConfiguration
import org.geotools.util.ContentFormatException
import org.geotools.xsd.Parser
import org.locationtech.jts.geom.Geometry

@Named
class GeometryFileParser(private val objectMapper: ObjectMapper) {
  fun parse(content: ByteArray, filename: String?): Geometry {
    val detectedContentType =
        Tika().detect(content, filename)
            ?: throw ContentFormatException("Unable to determine file type")

    return when (detectedContentType) {
      "application/vnd.google-earth.kml+xml" -> parseKml(content.inputStream())
      "application/vnd.google-earth.kmz",
      "application/zip" -> parseKmz(content)

      // Tika can identify JSON files as text/plain.
      MediaType.APPLICATION_JSON,
      MediaType.TEXT_PLAIN -> parseGeoJson(content)
      else -> throw ContentFormatException("File type $detectedContentType not supported")
    }
  }

  private fun parseGeoJson(content: ByteArray): Geometry {
    return try {
      objectMapper.readValue<Geometry>(content)
    } catch (e: JsonParseException) {
      throw ContentFormatException("File does not appear to be valid GeoJSON")
    }
  }

  private fun parseKml(inputStream: InputStream): Geometry {
    val parentFeature =
        Parser(KMLConfiguration()).parse(inputStream) as? SimpleFeature
            ?: throw ContentFormatException("Unable to extract top-level information from KML file")
    val childFeatures =
        parentFeature.getAttribute("Feature") as? Collection<*>
            ?: throw ContentFormatException("No features found in KML file")
    val geometries =
        childFeatures.mapNotNull { (it as? SimpleFeature)?.defaultGeometry as? Geometry }

    if (geometries.isEmpty()) {
      throw ContentFormatException("No valid geometries found in KML file")
    }

    return geometries.reduce { a, b -> a.union(b) }.also { it.srid = SRID.LONG_LAT }
  }

  /** Parses a KMZ file, which is a KML file in a zip archive possibly alongside other files. */
  private fun parseKmz(content: ByteArray): Geometry {
    val tempFile = kotlin.io.path.createTempFile(suffix = ".zip")

    try {
      tempFile.writeBytes(content)

      val zipFile =
          try {
            ZipFile(tempFile.toFile())
          } catch (e: ZipException) {
            throw ContentFormatException("KMZ file does not appear to be a valid zip archive")
          }

      val zipEntry =
          zipFile.entries().asSequence().firstOrNull { it.name.endsWith(".kml", ignoreCase = true) }
              ?: throw ContentFormatException("No KML file found in archive")

      return zipFile.getInputStream(zipEntry).use { parseKml(it) }
    } finally {
      tempFile.deleteIfExists()
    }
  }
}
