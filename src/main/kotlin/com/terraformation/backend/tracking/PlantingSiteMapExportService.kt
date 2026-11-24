package com.terraformation.backend.tracking

import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.tracking.db.PlantingSiteHistoryIncompleteException
import com.terraformation.backend.tracking.db.PlantingSiteMapIncompleteException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.util.calculateAreaHectares
import jakarta.inject.Named
import java.math.BigDecimal
import org.locationtech.jts.geom.MultiPolygon
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Named
class PlantingSiteMapExportService(
    private val plantingSiteStore: PlantingSiteStore,
    transactionManager: PlatformTransactionManager,
) {
  private val readTransaction =
      TransactionTemplate(transactionManager).apply {
        isReadOnly = true
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
      }

  fun fetchLayers(
      siteId: PlantingSiteId,
      historyId: PlantingSiteHistoryId,
  ): List<PlantingSiteMapLayer> = readTransaction.execute {
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

    layers(
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

  fun fetchCurrentLayers(siteId: PlantingSiteId): List<PlantingSiteMapLayer> =
      readTransaction.execute {
        val site = plantingSiteStore.fetchSiteById(siteId, PlantingSiteDepth.Substratum)
        val boundary =
            site.boundary?.takeUnless { it.isEmpty }
                ?: throw PlantingSiteMapIncompleteException("Planting site $siteId has no boundary")
        val areaHa =
            site.areaHa
                ?: throw PlantingSiteMapIncompleteException("Planting site $siteId has no area")

        layers(
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
