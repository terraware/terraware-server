package com.terraformation.backend.tracking

import com.terraformation.backend.db.SRID
import com.terraformation.backend.file.useAndDelete
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import org.geotools.api.data.Transaction
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.geotools.referencing.CRS
import org.locationtech.jts.geom.MultiPolygon

internal object PlantingSiteShapefileWriter {
  private val extensions = listOf("shp", "shx", "dbf", "prj", "cpg")
  private val longitudeLatitudeCrs by lazy { CRS.decode("EPSG:${SRID.LONG_LAT}", true) }

  fun write(layers: List<PlantingSiteMapLayer>, output: OutputStream) {
    createTempDirectory().useAndDelete { directory ->
      ZipOutputStream(output).use { zip ->
        layers.forEach { layer ->
          writeLayer(layer, directory)
          extensions.forEach { extension ->
            val filename = "${layer.name}.$extension"
            zip.putNextEntry(ZipEntry(filename))
            Files.copy(directory.resolve(filename), zip)
            zip.closeEntry()
          }
        }
      }
    }
  }

  private fun writeLayer(layer: PlantingSiteMapLayer, directory: Path) {
    val shapefile = directory.resolve("${layer.name}.shp")
    val datastore = ShapefileDataStore(shapefile.toUri().toURL())
    try {
      datastore.charset = Charsets.UTF_8

      val schema =
          SimpleFeatureTypeBuilder()
              .apply {
                setName(layer.name)
                setCRS(longitudeLatitudeCrs)
                add("the_geom", MultiPolygon::class.java)
                if (layer.name != "exclusions") {
                  length(254)
                  add("name", String::class.java)
                }
                add("area_ha", Double::class.javaObjectType)
              }
              .buildFeatureType()
      datastore.createSchema(schema)
      if (layer.features.isNotEmpty()) {
        datastore.getFeatureWriterAppend(Transaction.AUTO_COMMIT).use { writer ->
          layer.features.forEach { feature ->
            val row = writer.next()
            row.defaultGeometry = feature.geometry
            feature.properties.forEach { (key, value) ->
              row.setAttribute(key, if (value is Number) value.toDouble() else value)
            }
            writer.write()
          }
        }
      }
    } finally {
      datastore.dispose()
    }
    directory.resolve("${layer.name}.cpg").writeText("UTF-8")
  }
}
