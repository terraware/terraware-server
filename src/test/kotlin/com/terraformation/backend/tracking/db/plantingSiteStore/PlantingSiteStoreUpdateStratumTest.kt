package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.tables.pojos.StrataRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.multiPolygon
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreUpdateStratumTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class UpdateStratum {
    @Test
    fun `updates editable values`() {
      val createdTime = Instant.ofEpochSecond(1000)
      val createdBy = insertUser()
      val plantingSiteId = insertPlantingSite(x = 0)

      val initialRow =
          StrataRow(
              areaHa = BigDecimal.ONE,
              boundaryModifiedBy = createdBy,
              boundaryModifiedTime = createdTime,
              boundary = multiPolygon(1),
              createdBy = createdBy,
              createdTime = createdTime,
              errorMargin = BigDecimal.TWO,
              plantingSiteId = plantingSiteId,
              modifiedBy = createdBy,
              modifiedTime = createdTime,
              name = "initial",
              numPermanentPlots = 1,
              numTemporaryPlots = 2,
              stableId = StableId("initial"),
              studentsT = BigDecimal.ONE,
              targetPlantingDensity = BigDecimal.ONE,
              variance = BigDecimal.ZERO,
          )

      val stratumId = insertStratum(initialRow)

      val newName = "renamed"
      val newErrorMargin = BigDecimal(10)
      val newStudentsT = BigDecimal(11)
      val newVariance = BigDecimal(12)
      val newPermanent = 13
      val newTemporary = 14
      val newTargetPlantingDensity = BigDecimal(13)

      val expected =
          initialRow.copy(
              errorMargin = newErrorMargin,
              id = stratumId,
              modifiedBy = user.userId,
              modifiedTime = clock.instant(),
              name = newName,
              numPermanentPlots = newPermanent,
              numTemporaryPlots = newTemporary,
              studentsT = newStudentsT,
              targetPlantingDensity = newTargetPlantingDensity,
              variance = newVariance,
          )

      store.updateStratum(stratumId) {
        it.copy(
            // Editable
            errorMargin = newErrorMargin,
            name = newName,
            numPermanentPlots = newPermanent,
            numTemporaryPlots = newTemporary,
            studentsT = newStudentsT,
            targetPlantingDensity = newTargetPlantingDensity,
            variance = newVariance,
            // Not editable
            boundaryModifiedBy = user.userId,
            boundaryModifiedTime = Instant.ofEpochSecond(5000),
            createdBy = user.userId,
            createdTime = Instant.ofEpochSecond(5000),
            modifiedBy = createdBy,
            modifiedTime = Instant.ofEpochSecond(5000),
        )
      }

      assertEquals(expected, strataDao.fetchOneById(stratumId))
    }

    @Test
    fun `updates full names of substrata if stratum is renamed`() {
      insertPlantingSite(x = 0)
      val stratumId1 = insertStratum(name = "initial 1")
      val substratumId1 = insertSubstratum(name = "sub 1")
      val substratumId2 = insertSubstratum(name = "sub 2")
      insertStratum(name = "initial 2")
      val substratumId3 = insertSubstratum(name = "sub 3")

      store.updateStratum(stratumId1) { it.copy(name = "renamed") }

      assertEquals(
          mapOf(
              substratumId1 to "renamed-sub 1",
              substratumId2 to "renamed-sub 2",
              substratumId3 to "initial 2-sub 3",
          ),
          substrataDao.findAll().associate { it.id to it.fullName },
      )
    }

    @Test
    fun `updates stratum name in current history entry`() {
      insertPlantingSite(x = 0)
      val stratumId = insertStratum(name = "initial")
      insertSubstratum(name = "substratum")

      clock.instant = Instant.ofEpochSecond(1000)

      insertPlantingSiteHistory(createdTime = clock.instant)
      val newStratumHistoryId = insertStratumHistory()
      val newSubstratumHistoryId = insertSubstratumHistory()

      val expectedSiteHistory = dslContext.fetch(PLANTING_SITE_HISTORIES)
      val expectedStratumHistory =
          dslContext.fetch(STRATUM_HISTORIES).onEach { record ->
            if (record.id == newStratumHistoryId) {
              record.name = "renamed"
            }
          }
      val expectedSubstratumHistory =
          dslContext.fetch(SUBSTRATUM_HISTORIES).onEach { record ->
            if (record.id == newSubstratumHistoryId) {
              record.fullName = "renamed-substratum"
            }
          }

      store.updateStratum(stratumId) { it.copy(name = "renamed") }

      assertTableEquals(expectedSiteHistory, "Planting site histories should not be affected")
      assertTableEquals(expectedStratumHistory)
      assertTableEquals(expectedSubstratumHistory)
    }

    @Test
    fun `throws exception if no permission`() {
      insertPlantingSite()
      val stratumId = insertStratum()

      every { user.canUpdatePlantingZone(stratumId) } returns false

      assertThrows<AccessDeniedException> { store.updateStratum(stratumId) { it } }
    }
  }
}
