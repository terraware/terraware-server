package com.terraformation.backend.tracking

import com.terraformation.backend.tracking.PlantingSiteMapWriterFixtures.expectedFile
import com.terraformation.backend.tracking.PlantingSiteMapWriterFixtures.layers
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.w3c.dom.Node

class PlantingSiteKmlWriterTest {
  @Test
  fun `writes KML folders with attributes and multipart polygons with holes`() {
    val expected = expectedFile("planting-site.kml").use(::normalizedXml)
    val output = ByteArrayOutputStream()

    PlantingSiteKmlWriter.write(layers, output)
    val actual = output.toByteArray().inputStream().use(::normalizedXml)

    assertEquals(expected, actual, "KML document")
  }

  private fun normalizedXml(input: InputStream): String {
    fun normalize(node: Node) {
      var child = node.firstChild
      while (child != null) {
        val next = child.nextSibling
        if (child.nodeType == Node.TEXT_NODE && child.nodeValue.isBlank()) {
          node.removeChild(child)
        } else if (child.nodeType == Node.TEXT_NODE && node.localName == "coordinates") {
          child.nodeValue = child.nodeValue.trim().replace(Regex("\\s+"), " ")
        } else {
          normalize(child)
        }
        child = next
      }
    }

    val document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(input)
    normalize(document)
    return StringWriter()
        .also { result ->
          TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            transform(DOMSource(document), StreamResult(result))
          }
        }
        .toString()
  }
}
