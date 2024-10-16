package com.terraformation.backend.api

import com.opencsv.CSVWriter
import io.ktor.utils.io.charsets.name
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * Constructs an HTTP response in GPX format.
 *
 * @param filename Filename that will be used by default if the user saves the GPX file. If null,
 *   response will not include a filename.
 * @param columnNames Contents of the first row of the CSV; this should be a list of the names of
 *   the columns, typically in human-readable form.
 * @param writeRows Callback function that writes the data rows to the CSV stream. This should call
 *   [CSVWriter.writeNext] for each data row.
 */
fun gpxResponse(
    filename: String?,
    waypoints: List<GpxWaypoint>,
): ResponseEntity<ByteArray> {
  val byteArrayOutputStream = ByteArrayOutputStream()

  GpxWriter(byteArrayOutputStream, waypoints).write()

  val value = byteArrayOutputStream.toByteArray()
  val headers = HttpHeaders()
  headers.contentLength = value.size.toLong()
  headers["Content-type"] = "application/gpx+xml;charset=UTF-8"

  if (filename != null) {
    headers.contentDisposition = ContentDisposition.attachment().filename(filename).build()
  }

  return ResponseEntity(value, headers, HttpStatus.OK)
}

// TODO: Add track, or routes data types if applicable
// https://www.topografix.com/GPX/1/1/gpx.xsd (wptType)
data class GpxWaypoint(val latitude: Double, val longitude: Double, val name: String?)

class GpxWriter(output: OutputStream, private val waypoints: List<GpxWaypoint>) {
  private val writer: XMLStreamWriter =
      XMLOutputFactory.newInstance().createXMLStreamWriter(output, StandardCharsets.UTF_8.name)

  private fun writeWaypoint(waypoint: GpxWaypoint) {
    writer.writeStartElement("wpt")
    writer.writeAttribute("lat", "${waypoint.latitude}")
    writer.writeAttribute("lon", "${waypoint.longitude}")

    writer.writeStartElement("name")
    writer.writeCharacters(waypoint.name)
    writer.writeEndElement()

    writer.writeEndElement()
  }

  fun write() {
    writer.writeStartDocument("utf-8", "1.0")
    writer.writeStartElement("gpx")
    waypoints.forEach { writeWaypoint(it) }

    // TODO: Write track, or routes if applicable

    writer.writeEndElement()
    writer.writeEndDocument()

    writer.flush()
    writer.close()
  }
}
