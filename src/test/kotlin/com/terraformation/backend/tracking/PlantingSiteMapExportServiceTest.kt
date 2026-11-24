package com.terraformation.backend.tracking

import com.terraformation.backend.multiPolygon
import com.terraformation.backend.tracking.db.PlantingSiteHistoryIncompleteException
import com.terraformation.backend.tracking.db.PlantingSiteMapIncompleteException
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.plantingSiteStore.BasePlantingSiteStoreTest
import com.terraformation.backend.util.calculateAreaHectares
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.MultiPolygon

internal class PlantingSiteMapExportServiceTest : BasePlantingSiteStoreTest() {
  private val service by lazy { PlantingSiteMapExportService(store, transactionManager) }

  @Test
  fun `exports the requested snapshot geometry and attributes with the current site name`() {
    val boundary = multiPolygon(0.5)
    val stratum = multiPolygon(0.4)
    val substratum = multiPolygon(0.3)
    val exclusion = multiPolygon(0.1)
    val siteId =
        insertPlantingSite(
            areaHa = BigDecimal(100),
            boundary = multiPolygon(1),
            insertHistory = false,
            name = "Current site name",
        )
    insertStratum(insertHistory = false, name = "Live stratum")
    insertSubstratum(insertHistory = false, name = "Live substratum")

    val historyId =
        insertPlantingSiteHistory(
            areaHa = BigDecimal(50),
            boundary = boundary,
            exclusion = exclusion,
        )
    insertStratumHistory(areaHa = BigDecimal(40), boundary = stratum, name = "Saved stratum")
    insertSubstratumHistory(
        areaHa = BigDecimal(30),
        boundary = substratum,
        name = "Saved substratum",
    )
    val simplifiedBoundary = multiPolygon(0.2)
    insertSimplifiedPlantingSiteHistory(boundary = simplifiedBoundary, exclusion = null)
    insertSimplifiedStratumHistory(boundary = simplifiedBoundary)
    insertSimplifiedSubstratumHistory(boundary = simplifiedBoundary)

    insertPlantingSiteHistory()
    insertStratumHistory()
    insertSubstratumHistory()

    val expected =
        listOf(
            namedLayer("site-boundary", boundary, "Current site name", 50),
            namedLayer("strata", stratum, "Saved stratum", 40),
            namedLayer("substrata", substratum, "Saved substratum", 30),
            exclusionLayer(exclusion),
        )
    assertEquals(expected, service.fetchLayers(siteId, historyId), "Unsimplified snapshot layers")
  }

  @Test
  fun `exports current layers independently of saved snapshots`() {
    val boundary = multiPolygon(1)
    val stratum = multiPolygon(0.8)
    val substratum = multiPolygon(0.6)
    val exclusion = multiPolygon(0.2)
    val siteId =
        insertPlantingSite(
            areaHa = BigDecimal(100),
            boundary = boundary,
            exclusion = exclusion,
            insertHistory = false,
            name = "Current site",
        )
    insertStratum(
        areaHa = BigDecimal(80),
        boundary = stratum,
        insertHistory = false,
        name = "Current stratum",
    )
    insertSubstratum(
        areaHa = BigDecimal(60),
        boundary = substratum,
        insertHistory = false,
        name = "Current substratum",
    )
    val savedBoundary = multiPolygon(0.4)
    insertSimplifiedPlantingSite(boundary = savedBoundary, exclusion = null)
    insertSimplifiedStratum(boundary = savedBoundary)
    insertSimplifiedSubstratum(boundary = savedBoundary)

    val expected =
        listOf(
            namedLayer("site-boundary", boundary, "Current site", 100),
            namedLayer("strata", stratum, "Current stratum", 80),
            namedLayer("substrata", substratum, "Current substratum", 60),
            exclusionLayer(exclusion),
        )
    assertEquals(expected, service.fetchCurrentLayers(siteId), "Live layers without history")

    insertPlantingSiteHistory(boundary = savedBoundary)
    insertStratumHistory()
    insertSubstratumHistory()

    assertEquals(expected, service.fetchCurrentLayers(siteId), "Live layers with history")
  }

  @Test
  fun `rejects a snapshot with a missing boundary or area`() {
    val boundary = multiPolygon(1)
    val siteId = insertPlantingSite(areaHa = BigDecimal.TEN, boundary = boundary)
    val missingSiteAreaId = insertPlantingSiteHistory(areaHa = null)
    val missingBoundaryId =
        insertPlantingSiteHistory(boundary = boundary.factory.createMultiPolygon())

    listOf(missingSiteAreaId, missingBoundaryId).forEach { historyId ->
      assertThrows<PlantingSiteHistoryIncompleteException> {
        service.fetchLayers(siteId, historyId)
      }
    }
  }

  @Test
  fun `rejects a current map with a missing boundary or area`() {
    val missingBoundaryId = insertPlantingSite(x = null)
    val missingAreaId = insertPlantingSite(areaHa = null)

    listOf(missingBoundaryId, missingAreaId).forEach { siteId ->
      assertThrows<PlantingSiteMapIncompleteException> { service.fetchCurrentLayers(siteId) }
    }
  }

  @Test
  fun `rejects a current map without permission to read the site`() {
    val siteId = insertPlantingSite(areaHa = BigDecimal.TEN, boundary = multiPolygon(1))
    every { user.canReadPlantingSite(siteId) } returns false

    assertThrows<PlantingSiteNotFoundException> { service.fetchCurrentLayers(siteId) }
  }

  private fun namedLayer(layer: String, boundary: MultiPolygon, name: String, areaHa: Int) =
      PlantingSiteMapLayer(
          layer,
          listOf(
              PlantingSiteMapFeature(
                  boundary,
                  mapOf("name" to name, "area_ha" to BigDecimal(areaHa)),
              )
          ),
      )

  private fun exclusionLayer(exclusion: MultiPolygon) =
      PlantingSiteMapLayer(
          "exclusions",
          listOf(
              PlantingSiteMapFeature(
                  exclusion,
                  mapOf("area_ha" to exclusion.calculateAreaHectares()),
              )
          ),
      )
}
