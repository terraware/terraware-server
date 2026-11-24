package com.terraformation.backend.tracking

import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.tracking.db.PlantingSiteHistoryIncompleteException
import com.terraformation.backend.tracking.db.PlantingSiteMapIncompleteException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.util.calculateAreaHectares
import java.math.BigDecimal
import org.locationtech.jts.geom.MultiPolygon
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class PlantingSiteMapFeature(
    val geometry: MultiPolygon,
    val properties: Map<String, Any>,
) {
  companion object {
    fun named(geometry: MultiPolygon, name: String, areaHa: BigDecimal) =
        PlantingSiteMapFeature(geometry, mapOf("name" to name, "area_ha" to areaHa))
  }
}

data class PlantingSiteMapLayer(val name: String, val features: List<PlantingSiteMapFeature>)

@Service
class PlantingSiteMapExporter(private val plantingSiteStore: PlantingSiteStore) {
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
        boundary = PlantingSiteMapFeature.named(history.boundary, site.name, areaHa),
        exclusion = history.exclusion,
        strata =
            history.strata.map { PlantingSiteMapFeature.named(it.boundary, it.name, it.areaHa) },
        substrata =
            history.strata.flatMap { stratum ->
              stratum.substrata.map {
                PlantingSiteMapFeature.named(it.boundary, it.name, it.areaHa)
              }
            },
    )
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  fun fetchCurrentLayers(siteId: PlantingSiteId): List<PlantingSiteMapLayer> {
    val site = plantingSiteStore.fetchSiteById(siteId, PlantingSiteDepth.Substratum)
    val boundary =
        site.boundary?.takeUnless { it.isEmpty }
            ?: throw PlantingSiteMapIncompleteException("Planting site $siteId has no boundary")
    val areaHa =
        site.areaHa ?: throw PlantingSiteMapIncompleteException("Planting site $siteId has no area")

    return layers(
        boundary = PlantingSiteMapFeature.named(boundary, site.name, areaHa),
        exclusion = site.exclusion,
        strata = site.strata.map { PlantingSiteMapFeature.named(it.boundary, it.name, it.areaHa) },
        substrata =
            site.strata.flatMap { stratum ->
              stratum.substrata.map {
                PlantingSiteMapFeature.named(it.boundary, it.name, it.areaHa)
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
}
