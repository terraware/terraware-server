package com.terraformation.backend.api

import io.ktor.utils.io.charsets.name
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
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
 * @param waypoints Waypoints to be written to the GPX file.
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
data class GpxWaypoint(val latitude: BigDecimal, val longitude: BigDecimal, val name: String?) {
  constructor(
      latitude: Double,
      longitude: Double,
      name: String?,
  ) : this(
      BigDecimal(latitude),
      BigDecimal(longitude),
      name,
  )
}

class GpxWriter(output: OutputStream, private val waypoints: List<GpxWaypoint>) {
  private val writer: XMLStreamWriter =
      XMLOutputFactory.newInstance().createXMLStreamWriter(output, StandardCharsets.UTF_8.name)

  private val MAX_DECIMAL_PLACE = 8 // precision to about 1mm for latitude longitude

  private fun writeWaypoint(waypoint: GpxWaypoint) {
    writer.writeStartElement("wpt")

    val roundedLatitude = waypoint.latitude.setScale(MAX_DECIMAL_PLACE, RoundingMode.HALF_UP)
    val roundedLongitude = waypoint.longitude.setScale(MAX_DECIMAL_PLACE, RoundingMode.HALF_UP)

    writer.writeAttribute("lat", roundedLatitude.toPlainString())
    writer.writeAttribute("lon", roundedLongitude.toPlainString())

    writer.writeStartElement("name")
    writer.writeCharacters(waypoint.name)
    writer.writeEndElement()

    writer.writeEndElement()
  }

  fun write() {
    writer.writeStartDocument("utf-8", "1.0")
    writer.writeStartElement("gpx")
    writer.writeAttribute("creator", "Terraformation")
    writer.writeAttribute("xmlns", "http://www.topografix.com/GPX/1/1")
    writer.writeAttribute("version", "1.1")
    waypoints.forEach { writeWaypoint(it) }

    // TODO: Write track, or routes if applicable

    writer.writeEndElement()
    writer.writeEndDocument()

    writer.close()
  }
}
