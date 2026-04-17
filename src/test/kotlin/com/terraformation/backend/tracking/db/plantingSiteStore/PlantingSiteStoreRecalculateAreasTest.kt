package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.point
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.differenceNullable
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.MultiPolygon

internal class PlantingSiteStoreRecalculateAreasTest : BasePlantingSiteStoreTest() {
  private val gridOrigin = point(1)
  private val siteBoundary: MultiPolygon = Turtle(gridOrigin).makeMultiPolygon { square(150) }
  private val exclusion: MultiPolygon =
      Turtle(gridOrigin).makeMultiPolygon {
        east(50)
        north(50)
        square(50)
      }

  private val successMessages = mutableListOf<String>()
  private val failureMessages = mutableListOf<String>()

  @Test
  fun `recalculates site stratum and substratum areas at higher precision`() {
    val siteId =
        insertPlantingSite(
            boundary = siteBoundary,
            exclusion = exclusion,
            gridOrigin = gridOrigin,
            areaHa = BigDecimal("2.0"),
        )
    val stratumId = insertStratum(boundary = siteBoundary, areaHa = BigDecimal("2.0"))
    val substratumId = insertSubstratum(boundary = siteBoundary, areaHa = BigDecimal("2.0"))

    store.recalculatePlantingSiteAreas(successMessages::add, failureMessages::add)

    val expectedArea = siteBoundary.differenceNullable(exclusion).calculateAreaHectares()

    assertEquals(expectedArea, fetchSiteArea(siteId), "Site area")
    assertEquals(expectedArea, fetchStratumArea(stratumId), "Stratum area")
    assertEquals(expectedArea, fetchSubstratumArea(substratumId), "Substratum area")
    assertTrue(
        expectedArea < siteBoundary.calculateAreaHectares(),
        "Exclusion should reduce the recalculated area",
    )
    assertEquals(emptyList<String>(), failureMessages)
  }

  @Test
  fun `skips sites without a boundary`() {
    val siteId = insertPlantingSite(areaHa = null)

    store.recalculatePlantingSiteAreas(successMessages::add, failureMessages::add)

    assertNull(fetchSiteArea(siteId), "Site without boundary should be unchanged")
    assertEquals(emptyList<String>(), successMessages)
    assertEquals(emptyList<String>(), failureMessages)
  }

  @Test
  fun `continues processing other sites after a failure`() {
    val failingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)
    val succeedingSiteId =
        insertPlantingSite(
            boundary = siteBoundary,
            gridOrigin = gridOrigin,
            areaHa = BigDecimal("2.3"),
        )

    every { user.canReadPlantingSite(failingSiteId) } returns false

    store.recalculatePlantingSiteAreas(successMessages::add, failureMessages::add)

    assertEquals(siteBoundary.calculateAreaHectares(), fetchSiteArea(succeedingSiteId))
    assertEquals(1, failureMessages.size, "One failure message expected")
    assertTrue(
        failureMessages.single().contains("$failingSiteId"),
        "Failure message should mention failing site ID: ${failureMessages.single()}",
    )
    assertNotNull(successMessages.firstOrNull { it.contains("$succeedingSiteId") })
  }

  private fun fetchSiteArea(siteId: PlantingSiteId): BigDecimal? =
      dslContext
          .select(PLANTING_SITES.AREA_HA)
          .from(PLANTING_SITES)
          .where(PLANTING_SITES.ID.eq(siteId))
          .fetchOne(PLANTING_SITES.AREA_HA)

  private fun fetchStratumArea(stratumId: StratumId): BigDecimal? =
      dslContext
          .select(STRATA.AREA_HA)
          .from(STRATA)
          .where(STRATA.ID.eq(stratumId))
          .fetchOne(STRATA.AREA_HA)

  private fun fetchSubstratumArea(substratumId: SubstratumId): BigDecimal? =
      dslContext
          .select(SUBSTRATA.AREA_HA)
          .from(SUBSTRATA)
          .where(SUBSTRATA.ID.eq(substratumId))
          .fetchOne(SUBSTRATA.AREA_HA)
}
