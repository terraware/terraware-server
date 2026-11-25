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
  private val service by lazy { PlantingSiteMapExportService(store) }

  @Test
  fun `exports the requested snapshot geometry and attributes with the current site name`() {
    val snapshotBoundary = multiPolygon(0.5)
    val stratum = multiPolygon(0.4)
    val substratum = multiPolygon(0.3)
    val exclusion = multiPolygon(0.1)

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
            boundary = snapshotBoundary,
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

    insertPlantingSiteHistory(
        boundary = multiPolygon(0.9),
        createdTime = clock.instant().plusSeconds(1),
    )
    insertStratumHistory()
    insertSubstratumHistory()

    val expected =
        listOf(
            namedLayer("site-boundary", snapshotBoundary, "Current site name", 50),
            namedLayer("strata", stratum, "Saved stratum", 40),
            namedLayer("substrata", substratum, "Saved substratum", 30),
            exclusionLayer(exclusion),
        )

    val actual = service.fetchLayers(inserted.plantingSiteId, historyId)

    assertEquals(expected, actual, "Unsimplified snapshot layers")
  }

  @Test
  fun `exports current layers independently of saved snapshots`() {
    val liveBoundary = multiPolygon(1)
    val stratum = multiPolygon(0.8)
    val substratum = multiPolygon(0.6)
    val exclusion = multiPolygon(0.2)

    insertPlantingSite(
        areaHa = BigDecimal(100),
        boundary = liveBoundary,
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

    val simplifiedBoundary = multiPolygon(0.4)
    insertSimplifiedPlantingSite(boundary = simplifiedBoundary, exclusion = null)
    insertSimplifiedStratum(boundary = simplifiedBoundary)
    insertSimplifiedSubstratum(boundary = simplifiedBoundary)

    val expected =
        listOf(
            namedLayer("site-boundary", liveBoundary, "Current site", 100),
            namedLayer("strata", stratum, "Current stratum", 80),
            namedLayer("substrata", substratum, "Current substratum", 60),
            exclusionLayer(exclusion),
        )

    val layersWithoutHistory = service.fetchCurrentLayers(inserted.plantingSiteId)

    assertEquals(expected, layersWithoutHistory, "Live layers without history")

    insertPlantingSiteHistory(boundary = multiPolygon(0.5))
    insertStratumHistory()
    insertSubstratumHistory()

    val layersWithHistory = service.fetchCurrentLayers(inserted.plantingSiteId)

    assertEquals(expected, layersWithHistory, "Live layers with history")
  }

  @Test
  fun `rejects a snapshot with an empty boundary`() {
    val boundary = multiPolygon(1)
    insertPlantingSite(areaHa = BigDecimal.TEN, boundary = boundary)
    insertPlantingSiteHistory(boundary = boundary.factory.createMultiPolygon())

    assertThrows<PlantingSiteHistoryIncompleteException> {
      service.fetchLayers(inserted.plantingSiteId, inserted.plantingSiteHistoryId)
    }
  }

  @Test
  fun `rejects a snapshot with a missing area`() {
    insertPlantingSite(areaHa = BigDecimal.TEN, boundary = multiPolygon(1))
    insertPlantingSiteHistory(areaHa = null)

    assertThrows<PlantingSiteHistoryIncompleteException> {
      service.fetchLayers(inserted.plantingSiteId, inserted.plantingSiteHistoryId)
    }
  }

  @Test
  fun `rejects a current map with a missing boundary`() {
    insertPlantingSite(areaHa = BigDecimal.TEN, x = null)

    assertThrows<PlantingSiteMapIncompleteException> {
      service.fetchCurrentLayers(inserted.plantingSiteId)
    }
  }

  @Test
  fun `rejects a current map with a missing area`() {
    insertPlantingSite(areaHa = null, boundary = multiPolygon(1))

    assertThrows<PlantingSiteMapIncompleteException> {
      service.fetchCurrentLayers(inserted.plantingSiteId)
    }
  }

  @Test
  fun `rejects a current map without permission to read the site`() {
    insertPlantingSite(areaHa = BigDecimal.TEN, boundary = multiPolygon(1))
    every { user.canReadPlantingSite(inserted.plantingSiteId) } returns false

    assertThrows<PlantingSiteNotFoundException> {
      service.fetchCurrentLayers(inserted.plantingSiteId)
    }
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
