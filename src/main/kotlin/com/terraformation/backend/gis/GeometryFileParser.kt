package com.terraformation.backend.gis

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.db.SRID
import com.terraformation.backend.file.useAndDelete
import com.terraformation.backend.tracking.model.Shapefile
import jakarta.inject.Named
import jakarta.ws.rs.core.MediaType
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes
import org.apache.tika.Tika
import org.geotools.api.feature.simple.SimpleFeature
import org.geotools.kml.v22.KMLConfiguration
import org.geotools.util.ContentFormatException
import org.geotools.xsd.Parser
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection

@Named
class GeometryFileParser(private val objectMapper: ObjectMapper) {
  companion object {
    private const val MAX_COMPONENT_BYTES = 100L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
  }

  fun parse(content: ByteArray, filename: String?): Geometry {
    val detectedContentType =
        Tika().detect(content, filename)
            ?: throw ContentFormatException("Unable to determine file type")

    return when (detectedContentType) {
      "application/vnd.google-earth.kml+xml" -> parseKml(content.inputStream())
      "application/vnd.google-earth.kmz",
      "application/zip" -> parseZip(content)

      // Tika can identify JSON files as text/plain.
      MediaType.APPLICATION_JSON,
      MediaType.TEXT_PLAIN -> parseGeoJson(content)
      else -> throw ContentFormatException("File type $detectedContentType not supported")
    }
  }

  private fun parseGeoJson(content: ByteArray): Geometry {
    return try {
      val geometry = objectMapper.readValue<Geometry>(content)
      if (geometry is GeometryCollection) geometry.union() else geometry
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
    val geometries = childFeatures.mapNotNull {
      (it as? SimpleFeature)?.defaultGeometry as? Geometry
    }

    if (geometries.isEmpty()) {
      throw ContentFormatException("No valid geometries found in KML file")
    }

    return geometries.reduce { a, b -> a.union(b) }.also { it.srid = SRID.LONG_LAT }
  }

  /** Parses an archive containing KML or a shapefile and its secondary files. */
  private fun parseZip(content: ByteArray): Geometry {
    return createTempFile(suffix = ".zip").useAndDelete { tempFile ->
      tempFile.writeBytes(content)

      val zipFile =
          try {
            ZipFile(tempFile.toFile())
          } catch (e: ZipException) {
            throw ContentFormatException("File does not appear to be a valid zip archive")
          }

      zipFile.use { zip ->
        parseZippedKml(zip) ?: parseZippedShapefile(zip)
      }
    }
  }

  private fun parseZippedKml(zip: ZipFile): Geometry? {
    val entry =
        zip.entries().asSequence().firstOrNull {
          !it.isDirectory && it.name.endsWith(".kml", ignoreCase = true)
        } ?: return null

    return zip.getInputStream(entry).use { parseKml(it) }
  }

  private fun parseZippedShapefile(zip: ZipFile): Geometry {
    val entries =
        zip.entries()
            .asSequence()
            .filter {
              !it.isDirectory && !it.name.substringAfterLast('/').startsWith('.')
            }
            .toList()
    val filenames = entries.map { it.name }
    val shapefiles = filenames.filter { it.endsWith(".shp", ignoreCase = true) }
    if (shapefiles.isEmpty()) {
      throw ContentFormatException("No KML or SHP file found in archive")
    }
    if (shapefiles.size != 1) {
      throw ContentFormatException("Archive must contain exactly one .shp file")
    }

    val basename = shapefiles.single().substringBeforeLast('.')
    val components =
        listOf("shp", "shx", "dbf", "prj").associateWith { extension ->
          entries.singleOrNull { it.name.equals("$basename.$extension", ignoreCase = true) }
              ?: throw ContentFormatException(
                  "Archive must contain exactly one $basename.$extension"
              )
        }
    if (components.values.any { it.size > MAX_COMPONENT_BYTES }) {
      throw ContentFormatException(
          "Shapefile component exceeds $MAX_COMPONENT_BYTES uncompressed bytes"
      )
    }
    if (components.values.sumOf { maxOf(0L, it.size) } > MAX_TOTAL_BYTES) {
      throw ContentFormatException("Shapefile exceeds $MAX_TOTAL_BYTES total uncompressed bytes")
    }

    val geometries =
        createTempDirectory().useAndDelete { directory ->
          var totalBytes = 0L
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          for ((extension, entry) in components) {
            zip.getInputStream(entry).use { input ->
              directory.resolve("boundary.$extension").outputStream().use { output ->
                var componentBytes = 0L
                while (true) {
                  val count = input.read(buffer)
                  if (count < 0) break
                  componentBytes += count
                  totalBytes += count
                  if (componentBytes > MAX_COMPONENT_BYTES) {
                    throw ContentFormatException(
                        "Shapefile component exceeds $MAX_COMPONENT_BYTES uncompressed bytes"
                    )
                  }
                  if (totalBytes > MAX_TOTAL_BYTES) {
                    throw ContentFormatException(
                        "Shapefile exceeds $MAX_TOTAL_BYTES total uncompressed bytes"
                    )
                  }
                  output.write(buffer, 0, count)
                }
              }
            }
          }
          Shapefile.fromFiles(directory.resolve("boundary.shp")).features.map { it.geometry }
        }
    if (geometries.isEmpty()) {
      throw ContentFormatException("No valid geometries found in shapefile")
    }
    return geometries.reduce { a, b -> a.union(b) }.also { it.srid = SRID.LONG_LAT }
  }
}
