package com.terraformation.backend.tracking

import com.terraformation.backend.db.SRID
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import org.assertj.core.api.Assertions.assertThat
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.referencing.CRS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.io.WKTReader

class PlantingSiteShapefileWriterTest {
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
  fun `writes complete WGS84 shapefiles with UTF-8 names and numeric areas`(
      @TempDir directory: Path
  ) {
    val output = ByteArrayOutputStream()
    PlantingSiteShapefileWriter.write(layers, output)
    val filenames =
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
          generateSequence { zip.nextEntry }
              .map { entry ->
                entry.name.also { directory.resolve(it).writeBytes(zip.readBytes()) }
              }
              .toSet()
        }
    val expectedFiles =
        setOf("shp", "shx", "dbf", "prj", "cpg")
            .flatMap { listOf("strata.$it", "substrata.$it", "exclusions.$it") }
            .toSet()
    assertEquals(expectedFiles, filenames, "Shapefile archive entries")
    assertEquals("UTF-8", directory.resolve("substrata.cpg").readText(), "DBF character encoding")

    val shapefile = directory.resolve("substrata.shp")
    val datastore = ShapefileDataStore(shapefile.toUri().toURL())
    try {
      datastore.charset = Charsets.UTF_8
      assertEquals(
          SRID.LONG_LAT,
          CRS.lookupEpsgCode(datastore.schema.coordinateReferenceSystem, true),
          "Shapefile EPSG code",
      )
      datastore.featureSource.features.features().use { reader ->
        val feature = reader.next()
        assertThat(feature.defaultGeometry as MultiPolygon)
            .matches({ boundary.equalsTopo(it) }, "topologically matches the original boundary")
        assertEquals(substratumName, feature.getAttribute("name"), "Substratum name")
        assertEquals(2.5, feature.getAttribute("area_ha"), "Substratum area in hectares")
      }
    } finally {
      datastore.dispose()
    }

    mapOf("strata" to setOf("name", "area_ha"), "exclusions" to setOf("area_ha")).forEach {
        (layer, fields) ->
      val layerStore = ShapefileDataStore(directory.resolve("$layer.shp").toUri().toURL())
      try {
        val descriptors = layerStore.schema.attributeDescriptors.map { it.localName }.toSet()
        assertEquals(fields + "the_geom", descriptors, "$layer schema fields")
        if (layer == "exclusions") {
          layerStore.featureSource.features.features().use { reader ->
            assertEquals(1.5, reader.next().getAttribute("area_ha"), "Exclusion area in hectares")
          }
        }
      } finally {
        layerStore.dispose()
      }
    }
  }
}
