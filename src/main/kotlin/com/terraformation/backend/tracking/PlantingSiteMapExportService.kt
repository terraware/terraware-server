package com.terraformation.backend.tracking

import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.tracking.db.PlantingSiteHistoryIncompleteException
import com.terraformation.backend.tracking.db.PlantingSiteMapIncompleteException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.util.calculateAreaHectares
import jakarta.inject.Named
import java.io.OutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.geojson.OrientationTransformer
import tools.jackson.core.StreamWriteFeature
import tools.jackson.databind.ObjectMapper

enum class PlantingSiteMapFormat(val extension: String, val mediaType: String) {
  GeoJson("zip", "application/zip"),
}

@Named
class PlantingSiteMapExportService(
    private val objectMapper: ObjectMapper,
    private val plantingSiteStore: PlantingSiteStore,
) {
  fun fetchLayers(
      siteId: PlantingSiteId,
      historyId: PlantingSiteHistoryId,
  ): List<PlantingSiteMapLayer> {
    val history =
        plantingSiteStore.fetchSiteHistoryById(siteId, historyId, PlantingSiteDepth.Substratum)
    if (history.boundary.isEmpty) {
      throw PlantingSiteHistoryIncompleteException(
          "Planting site history $historyId has no boundary"
      )
    }
    val areaHa =
        history.areaHa
            ?: throw PlantingSiteHistoryIncompleteException(
                "Planting site history $historyId has no area"
            )
    val site = plantingSiteStore.fetchSiteById(siteId, PlantingSiteDepth.Site)

    return layers(
        boundary = PlantingSiteMapFeature(history.boundary, site.name, areaHa),
        exclusion = history.exclusion,
        strata = history.strata.map { PlantingSiteMapFeature(it.boundary, it.name, it.areaHa) },
        substrata =
            history.strata.flatMap { stratum ->
              stratum.substrata.map {
                PlantingSiteMapFeature(it.boundary, it.name, it.areaHa)
              }
            },
    )
  }

  fun fetchCurrentLayers(siteId: PlantingSiteId): List<PlantingSiteMapLayer> {
    val site = plantingSiteStore.fetchSiteById(siteId, PlantingSiteDepth.Substratum)
    val boundary =
        site.boundary?.takeUnless { it.isEmpty }
            ?: throw PlantingSiteMapIncompleteException("Planting site $siteId has no boundary")
    val areaHa =
        site.areaHa ?: throw PlantingSiteMapIncompleteException("Planting site $siteId has no area")

    return layers(
        boundary = PlantingSiteMapFeature(boundary, site.name, areaHa),
        exclusion = site.exclusion,
        strata = site.strata.map { PlantingSiteMapFeature(it.boundary, it.name, it.areaHa) },
        substrata =
            site.strata.flatMap { stratum ->
              stratum.substrata.map {
                PlantingSiteMapFeature(it.boundary, it.name, it.areaHa)
              }
            },
    )
  }

  private fun layers(
      boundary: PlantingSiteMapFeature,
      exclusion: MultiPolygon?,
      strata: List<PlantingSiteMapFeature>,
      substrata: List<PlantingSiteMapFeature>,
  ): List<PlantingSiteMapLayer> = buildList {
    add(PlantingSiteMapLayer("site-boundary", listOf(boundary)))
    add(PlantingSiteMapLayer("strata", strata))
    add(PlantingSiteMapLayer("substrata", substrata))
    exclusion
        ?.takeUnless { it.isEmpty }
        ?.let { geometry ->
          val feature =
              PlantingSiteMapFeature(geometry, mapOf("area_ha" to geometry.calculateAreaHectares()))
          add(PlantingSiteMapLayer("exclusions", listOf(feature)))
        }
  }

  fun write(
      format: PlantingSiteMapFormat,
      layers: List<PlantingSiteMapLayer>,
      output: OutputStream,
  ) {
    when (format) {
      PlantingSiteMapFormat.GeoJson -> writeGeoJson(layers, output)
    }
  }

  private fun writeGeoJson(layers: List<PlantingSiteMapLayer>, output: OutputStream) {
    val writer = objectMapper.writer().without(StreamWriteFeature.AUTO_CLOSE_TARGET)
    ZipOutputStream(output).use { zip ->
      layers.forEach { layer ->
        zip.putNextEntry(ZipEntry("${layer.name}.geojson"))
        val features =
            layer.features.map {
              val geometry =
                  it.geometry.factory.createMultiPolygon(
                      Array(it.geometry.numGeometries) { index ->
                        OrientationTransformer.transformCCW(
                            it.geometry.getGeometryN(index) as Polygon
                        )
                      }
                  )
              geometry.srid = it.geometry.srid
              mapOf("type" to "Feature", "geometry" to geometry, "properties" to it.properties)
            }
        writer.writeValue(zip, mapOf("type" to "FeatureCollection", "features" to features))
        zip.closeEntry()
      }
    }
  }
}

data class PlantingSiteMapFeature(
    val geometry: MultiPolygon,
    val properties: Map<String, Any>,
) {
  constructor(
      geometry: MultiPolygon,
      name: String,
      areaHa: BigDecimal,
  ) : this(geometry, mapOf("name" to name, "area_ha" to areaHa))
}

data class PlantingSiteMapLayer(val name: String, val features: List<PlantingSiteMapFeature>)
