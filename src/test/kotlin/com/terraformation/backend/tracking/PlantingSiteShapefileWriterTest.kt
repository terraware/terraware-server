package com.terraformation.backend.tracking

import com.terraformation.backend.db.GeometrySerializer
import com.terraformation.backend.db.SRID
import com.terraformation.backend.tracking.PlantingSiteMapWriterFixtures.expectedFile
import com.terraformation.backend.tracking.PlantingSiteMapWriterFixtures.layers
import com.terraformation.backend.tracking.PlantingSiteMapWriterFixtures.objectMapper
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.math.pow
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.referencing.CRS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.precision.GeometryPrecisionReducer
import tools.jackson.databind.JsonNode

class PlantingSiteShapefileWriterTest {
  private data class LayerMetadata(val epsgCode: Int?, val encoding: String)

  private data class DecodedFeature(val geometry: Geometry, val properties: JsonNode)

  @Test
  fun `writes complete WGS84 shapefiles with UTF-8 names and numeric areas`(
      @TempDir directory: Path
  ) {
    val fieldsByLayer =
        mapOf(
            "site-boundary" to setOf("name", "area_ha"),
            "strata" to setOf("name", "area_ha"),
            "substrata" to setOf("name", "area_ha"),
            "exclusions" to setOf("area_ha"),
        )
    val extensions = listOf("shp", "shx", "dbf", "prj", "cpg")
    val expectedEntries = fieldsByLayer.keys.flatMap { layer -> extensions.map { "$layer.$it" } }
    val expectedFeatures = fieldsByLayer.keys.associateWith(::expectedFeatures)
    val expectedMetadata =
        fieldsByLayer.keys.associateWith { LayerMetadata(SRID.LONG_LAT, "UTF-8") }
    val output = ByteArrayOutputStream()

    PlantingSiteShapefileWriter.write(layers, output)
    val entries = unzip(output.toByteArray(), directory)

    assertEquals(expectedEntries.sorted(), entries.sorted(), "Shapefile archive entries")

    fieldsByLayer.forEach { (layer, fields) ->
      val datastore = ShapefileDataStore(directory.resolve("$layer.shp").toUri().toURL())
      try {
        datastore.charset = Charsets.UTF_8
        val metadata =
            LayerMetadata(
                CRS.lookupEpsgCode(datastore.schema.coordinateReferenceSystem, true),
                directory.resolve("$layer.cpg").readText(),
            )
        val fieldNames = datastore.schema.attributeDescriptors.map { it.localName }.toSet()
        val features =
            datastore.featureSource.features.features().use { reader ->
              buildList {
                while (reader.hasNext()) {
                  val feature = reader.next()
                  val properties = (fieldNames - "the_geom").associateWith(feature::getAttribute)
                  add(
                      DecodedFeature(
                          normalizedGeometry(feature.defaultGeometry as Geometry),
                          objectMapper.valueToTree(properties),
                      )
                  )
                }
              }
            }

        assertEquals(expectedMetadata.getValue(layer), metadata, "$layer CRS and encoding")
        assertEquals(fields + "the_geom", fieldNames, "$layer schema fields")
        assertEquals(expectedFeatures.getValue(layer), features, "$layer decoded features")
      } finally {
        datastore.dispose()
      }
    }
  }

  private fun unzip(bytes: ByteArray, directory: Path): List<String> =
      ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }
            .map { entry -> entry.name.also { directory.resolve(it).writeBytes(zip.readBytes()) } }
            .toList()
      }

  private fun expectedFeatures(layer: String): List<DecodedFeature> =
      expectedFile("$layer.geojson").use { input ->
        objectMapper
            .readTree(input)["features"]
            .asSequence()
            .map { feature ->
              DecodedFeature(
                  normalizedGeometry(
                      objectMapper.treeToValue(feature["geometry"], Geometry::class.java)
                  ),
                  feature["properties"],
              )
            }
            .toList()
      }

  private fun normalizedGeometry(geometry: Geometry): Geometry =
      GeometryPrecisionReducer.reducePointwise(
              geometry,
              PrecisionModel(10.0.pow(GeometrySerializer.COORDINATE_VALUE_SCALE)),
          )
          .norm()
}
