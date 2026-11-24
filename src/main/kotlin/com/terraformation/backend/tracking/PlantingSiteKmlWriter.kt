package com.terraformation.backend.tracking

import java.io.OutputStream
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter
import org.locationtech.jts.algorithm.Orientation
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon

internal object PlantingSiteKmlWriter {
  fun write(layers: List<PlantingSiteMapLayer>, output: OutputStream) {
    val xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8")
    try {
      Document(xml).write(layers)
    } finally {
      xml.close()
    }
  }

  private class Document(private val xml: XMLStreamWriter) {
    fun write(layers: List<PlantingSiteMapLayer>) {
      xml.writeStartDocument("UTF-8", "1.0")
      element("kml") {
        xml.writeDefaultNamespace("http://www.opengis.net/kml/2.2")
        element("Document") {
          layers.forEach { layer ->
            element("Folder") {
              textElement("name", layer.name)
              layer.features.forEach { writeFeature(it) }
            }
          }
        }
      }
      xml.writeEndDocument()
      xml.flush()
    }

    private fun writeFeature(feature: PlantingSiteMapFeature) {
      element("Placemark") {
        feature.properties["name"]?.let { textElement("name", it.toString()) }
        if (feature.properties.isNotEmpty()) {
          element("ExtendedData") {
            feature.properties.forEach { (key, value) ->
              element("Data") {
                xml.writeAttribute("name", key)
                textElement("value", value.toString())
              }
            }
          }
        }
        element("MultiGeometry") {
          (0..<feature.geometry.numGeometries).forEach { index ->
            val polygon = feature.geometry.getGeometryN(index) as Polygon
            element("Polygon") {
              element("outerBoundaryIs") {
                writeRing(polygon.exteriorRing, counterClockwise = true)
              }
              (0..<polygon.numInteriorRing).forEach { holeIndex ->
                element("innerBoundaryIs") {
                  writeRing(polygon.getInteriorRingN(holeIndex), counterClockwise = false)
                }
              }
            }
          }
        }
      }
    }

    private fun writeRing(ring: LinearRing, counterClockwise: Boolean) {
      val coordinates = ring.coordinateSequence
      val indices = 0..<coordinates.size()
      val ordered =
          if (Orientation.isCCW(coordinates) == counterClockwise) indices else indices.reversed()
      element("LinearRing") {
        element("coordinates") {
          ordered.forEachIndexed { index, coordinateIndex ->
            if (index > 0) {
              xml.writeCharacters(" ")
            }
            val x = coordinates.getX(coordinateIndex)
            val y = coordinates.getY(coordinateIndex)
            xml.writeCharacters("$x,$y")
          }
        }
      }
    }

    private inline fun element(name: String, content: () -> Unit) {
      xml.writeStartElement(name)
      content()
      xml.writeEndElement()
    }

    private fun textElement(name: String, text: String) {
      element(name) { xml.writeCharacters(text) }
    }
  }
}
