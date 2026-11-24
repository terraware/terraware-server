package com.terraformation.backend.tracking

import jakarta.inject.Named
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.geojson.OrientationTransformer
import tools.jackson.core.StreamWriteFeature
import tools.jackson.databind.ObjectMapper

@Named
class PlantingSiteGeoJsonWriter(objectMapper: ObjectMapper) {
  private val writer = objectMapper.writer().without(StreamWriteFeature.AUTO_CLOSE_TARGET)

  fun write(layers: List<PlantingSiteMapLayer>, output: OutputStream) {
    ZipOutputStream(output).use { zip ->
      layers.forEach { layer ->
        zip.putNextEntry(ZipEntry("${layer.name}.geojson"))
        val features = layer.features.map(::feature)
        writer.writeValue(zip, mapOf("type" to "FeatureCollection", "features" to features))
        zip.closeEntry()
      }
    }
  }

  private fun feature(feature: PlantingSiteMapFeature): Map<String, Any> {
    val geometry = feature.geometry
    val oriented =
        geometry.factory.createMultiPolygon(
            Array(geometry.numGeometries) { index ->
              OrientationTransformer.transformCCW(geometry.getGeometryN(index) as Polygon)
            }
        )
    oriented.srid = geometry.srid
    return mapOf("type" to "Feature", "geometry" to oriented, "properties" to feature.properties)
  }
}
