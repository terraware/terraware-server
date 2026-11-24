package com.terraformation.backend.tracking

import com.terraformation.backend.db.SRID
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.io.WKTReader
import org.w3c.dom.Element

class PlantingSiteMapWritersTest {
  private val boundary =
      WKTReader(GeometryFactory(PrecisionModel(), SRID.LONG_LAT))
          .read(
              "MULTIPOLYGON (((0 0, 0 3, 3 3, 3 0, 0 0), (1 1, 2 1, 2 2, 1 2, 1 1)), " +
                  "((4 4, 4 5, 5 5, 5 4, 4 4)))"
          ) as MultiPolygon
  private val substratumName = "Sous-strate <N°1>"
  private val layers =
      listOf(
          PlantingSiteMapLayer("strata", emptyList()),
          PlantingSiteMapLayer(
              "substrata",
              listOf(PlantingSiteMapFeature(boundary, substratumName, BigDecimal("2.5"))),
          ),
          PlantingSiteMapLayer(
              "exclusions",
              listOf(PlantingSiteMapFeature(boundary, mapOf("area_ha" to BigDecimal("1.5")))),
          ),
      )

  @Test
  fun `writes KML folders with attributes and multipart polygons with holes`() {
    val output = ByteArrayOutputStream()
    PlantingSiteKmlWriter.write(layers, output)
    val document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(output.toByteArray().inputStream())
    val namespace = "http://www.opengis.net/kml/2.2"
    val folders = document.getElementsByTagNameNS(namespace, "Folder")
    assertEquals(3, folders.length, "Layer folder count")
    val substrata = folders.item(1) as Element
    assertEquals(
        "substrata",
        substrata.getElementsByTagNameNS(namespace, "name").item(0).textContent,
        "Substrata folder name",
    )
    assertEquals(2, substrata.getElementsByTagNameNS(namespace, "Polygon").length, "Polygon count")
    assertEquals(
        1,
        substrata.getElementsByTagNameNS(namespace, "innerBoundaryIs").length,
        "Interior ring count",
    )
    val attributes = substrata.getElementsByTagNameNS(namespace, "Data")
    val properties =
        (0..<attributes.length).associate { index ->
          val data = attributes.item(index) as Element
          data.getAttribute("name") to
              data.getElementsByTagNameNS(namespace, "value").item(0).textContent
        }
    assertEquals(
        mapOf("name" to substratumName, "area_ha" to "2.5"),
        properties,
        "Placemark extended data",
    )
    val exclusions = folders.item(2) as Element
    val exclusionAttributes = exclusions.getElementsByTagNameNS(namespace, "Data")
    assertEquals(1, exclusionAttributes.length, "Exclusion attribute count")
    val exclusionArea = exclusionAttributes.item(0) as Element
    assertEquals("area_ha", exclusionArea.getAttribute("name"), "Exclusion attribute")
    assertEquals(
        "1.5",
        exclusionArea.getElementsByTagNameNS(namespace, "value").item(0).textContent,
        "Exclusion area in hectares",
    )
  }
}
