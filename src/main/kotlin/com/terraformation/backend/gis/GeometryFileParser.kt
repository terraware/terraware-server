package com.terraformation.backend.gis

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.terraformation.backend.db.GeometryDeserializer
import com.terraformation.backend.db.SRID
import com.terraformation.backend.file.useAndDelete
import com.terraformation.backend.tracking.model.Shapefile
import jakarta.inject.Named
import jakarta.ws.rs.core.MediaType
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
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
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.PrecisionModel
import org.xml.sax.SAXException

/**
 * Reads geometry from the file formats the clients can upload: KML, KMZ, GeoJSON, and ZIP archives
 * containing either a KML file or a single shapefile.
 */
@Named
class GeometryFileParser(private val objectMapper: ObjectMapper) {
  companion object {
    private const val MAX_COMPONENT_BYTES = 100L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    private val SUPPORTED_EXTENSIONS = setOf("kml", "kmz", "geojson", "json", "zip")

    fun hasSupportedExtension(filename: String?): Boolean =
        filename?.substringAfterLast('.', "")?.lowercase() in SUPPORTED_EXTENSIONS
  }

  private val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)

  fun parse(content: ByteArray, filename: String?): Geometry {
    return parseWithFormat(content, filename).geometry
  }

  /**
   * Reads a file and combines its shapes into a single geometry. Rejects invalid GeoJSON shapes.
   */
  fun parseWithFormat(content: ByteArray, filename: String?): ParsedGeometryFile {
    val parsed = read(content, filename, validateGeoJson = true)
    val elements = parsed.geometries
    if (elements.isEmpty()) {
      throw NoPolygonsException()
    }

    val combined = elements.reduce { a, b -> a.union(b) }

    return ParsedGeometryFile(
        if (elements.size == 1 && combined is GeometryCollection) combined.union() else combined,
        parsed.format,
    )
  }

  /**
   * Reads the individual shapes from a file without combining them or checking their topology. The
   * shape list may be empty; its elements are in WGS 84 coordinates.
   */
  fun readWithFormat(content: ByteArray, filename: String?): ParsedGeometryShapes =
      read(content, filename, validateGeoJson = false)

  private fun read(
      content: ByteArray,
      filename: String?,
      validateGeoJson: Boolean,
  ): ParsedGeometryShapes {
    val extension = filename?.substringAfterLast('.', "")?.lowercase()

    return when (Tika().detect(content, filename)) {
      "application/vnd.google-earth.kml+xml" -> readKml(content, GeometryFileFormat.KML)
      "application/vnd.google-earth.kmz",
      "application/zip" -> readZip(content)
      MediaType.APPLICATION_JSON -> readGeoJson(content, validateGeoJson)

      // Tika reports GeoJSON as text/plain unless the filename ends in .json, and callers don't
      // always have a usable filename to give us.
      MediaType.TEXT_PLAIN,
      MediaType.APPLICATION_OCTET_STREAM ->
          readByExtension(content, extension) ?: readGeoJson(content, validateGeoJson)

      MediaType.APPLICATION_XML,
      MediaType.TEXT_XML ->
          readByExtension(content, extension) ?: throw UnsupportedGeometryFileFormatException()

      else -> throw UnsupportedGeometryFileFormatException()
    }
  }

  /** Dispatches on the filename for content Tika can only identify approximately. */
  private fun readByExtension(content: ByteArray, extension: String?): ParsedGeometryShapes? =
      when (extension) {
        "kml" -> readKml(content, GeometryFileFormat.KML)
        "kmz",
        "zip" -> readZip(content)
        else -> null
      }

  private fun parsedFile(geometries: List<Geometry>, format: GeometryFileFormat) =
      ParsedGeometryShapes(
          // geometryFactory makes a copy of each geometry, setting the SRID to Long/Lat
          geometries.map { geometryFactory.createGeometry(it) },
          format,
      )

  private fun readGeoJson(content: ByteArray, validate: Boolean): ParsedGeometryShapes {
    val geometry =
        try {
          objectMapper
              .readerFor(Geometry::class.java)
              .withAttribute(GeometryDeserializer.SKIP_VALIDATION, !validate)
              .readValue<Geometry?>(content) ?: throw InvalidGeometryFileException()
        } catch (e: JsonProcessingException) {
          throw InvalidGeometryFileException(e)
        }

    return parsedFile(flattenCollections(geometry), GeometryFileFormat.GeoJSON)
  }

  /** Splits collections into their members, keeping multi-geometries such as MultiPolygon whole. */
  private fun flattenCollections(geometry: Geometry): List<Geometry> =
      if (geometry.javaClass == GeometryCollection::class.java) {
        (0 until geometry.numGeometries).flatMap { flattenCollections(geometry.getGeometryN(it)) }
      } else {
        listOf(geometry)
      }

  private fun readKml(content: ByteArray, format: GeometryFileFormat): ParsedGeometryShapes {
    validateKmlStructure(content)

    val root =
        try {
          Parser(KMLConfiguration()).parse(content.inputStream())
        } catch (e: SAXException) {
          throw InvalidGeometryFileException(e)
        } catch (e: IOException) {
          throw InvalidGeometryFileException(e)
        } catch (e: RuntimeException) {
          // GeoTools wraps failures to bind malformed coordinates in runtime exceptions.
          if (generateSequence<Throwable>(e) { it.cause }.any { it is IllegalArgumentException }) {
            throw InvalidGeometryFileException(e)
          }
          throw e
        }

    if (root !is SimpleFeature) {
      throw InvalidGeometryFileException()
    }

    return parsedFile(kmlGeometries(root), format)
  }

  /** GeoTools closes unclosed rings, so reject them before binding rather than repairing them. */
  private fun validateKmlStructure(content: ByteArray) {
    val factory =
        XMLInputFactory.newFactory().apply {
          setProperty(XMLInputFactory.SUPPORT_DTD, false)
          setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }

    try {
      val reader = factory.createXMLStreamReader(content.inputStream())
      try {
        var foundRoot = false
        var inRing = false

        while (reader.hasNext()) {
          when (reader.next()) {
            XMLStreamConstants.DTD -> throw InvalidGeometryFileException()
            XMLStreamConstants.START_ELEMENT -> {
              if (!foundRoot) {
                if (reader.localName != "kml") {
                  throw UnsupportedGeometryFileFormatException()
                }
                foundRoot = true
              }

              when (reader.localName) {
                "LinearRing" -> inRing = true
                "coordinates" -> if (inRing) validateRingCoordinates(reader.elementText)
              }
            }
            XMLStreamConstants.END_ELEMENT -> if (reader.localName == "LinearRing") inRing = false
          }
        }
      } finally {
        reader.close()
      }
    } catch (e: XMLStreamException) {
      throw InvalidGeometryFileException(e)
    } catch (e: NumberFormatException) {
      throw InvalidGeometryFileException(e)
    }
  }

  private fun validateRingCoordinates(text: String) {
    val coordinates =
        text.trim().split(Regex("\\s+")).map { tuple ->
          val values = tuple.split(',')
          if (values.size < 2) {
            throw InvalidGeometryFileException()
          }
          values[0].toDouble() to values[1].toDouble()
        }

    if (coordinates.size < 4 || coordinates.first() != coordinates.last()) {
      throw InvalidGeometryFileException()
    }
  }

  /** Collects the geometry of placemarks at any depth, including nested documents and folders. */
  private fun kmlGeometries(value: Any?): List<Geometry> =
      when (value) {
        is SimpleFeature ->
            (value.defaultGeometry as? Geometry)?.let { kmlGeometries(it) }
                ?: value.properties.flatMap { kmlGeometries(it.value) }
        is Geometry ->
            if (value is GeometryCollection && value !is MultiPolygon) {
              (0 until value.numGeometries).flatMap { kmlGeometries(value.getGeometryN(it)) }
            } else {
              listOf(value)
            }
        is Collection<*> -> value.flatMap { kmlGeometries(it) }
        else -> emptyList()
      }

  /** Parses an archive containing KML or a shapefile and its secondary files. */
  private fun readZip(content: ByteArray): ParsedGeometryShapes {
    return createTempFile(suffix = ".zip").useAndDelete { tempFile ->
      tempFile.writeBytes(content)

      val zipFile =
          try {
            ZipFile(tempFile.toFile())
          } catch (e: ZipException) {
            throw ContentFormatException("File does not appear to be a valid zip archive")
          }

      zipFile.use { zip ->
        readZippedKml(zip) ?: readZippedShapefile(zip)
      }
    }
  }

  private fun readZippedKml(zip: ZipFile): ParsedGeometryShapes? {
    val entry =
        zip.entries().asSequence().firstOrNull {
          !it.isDirectory && it.name.endsWith(".kml", ignoreCase = true)
        } ?: return null

    val content = zip.getInputStream(entry).use { it.readNBytes(MAX_COMPONENT_BYTES.toInt() + 1) }
    if (content.size > MAX_COMPONENT_BYTES) {
      throw ContentFormatException("KML exceeds $MAX_COMPONENT_BYTES uncompressed bytes")
    }

    return readKml(content, GeometryFileFormat.KMZ)
  }

  private fun readZippedShapefile(zip: ZipFile): ParsedGeometryShapes {
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
    return parsedFile(geometries, GeometryFileFormat.Shapefile)
  }
}
