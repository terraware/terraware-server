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

internal class PlantingSiteStoreUpdateZoneTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class UpdatePlantingZone {
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

      val plantingZoneId = insertStratum(initialRow)

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
              id = plantingZoneId,
              modifiedBy = user.userId,
              modifiedTime = clock.instant(),
              name = newName,
              numPermanentPlots = newPermanent,
              numTemporaryPlots = newTemporary,
              studentsT = newStudentsT,
              targetPlantingDensity = newTargetPlantingDensity,
              variance = newVariance,
          )

      store.updatePlantingZone(plantingZoneId) {
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

      assertEquals(expected, strataDao.fetchOneById(plantingZoneId))
    }

    @Test
    fun `updates full names of subzones if zone is renamed`() {
      insertPlantingSite(x = 0)
      val zoneId1 = insertStratum(name = "initial 1")
      val subzoneId1 = insertSubstratum(name = "sub 1")
      val subzoneId2 = insertSubstratum(name = "sub 2")
      insertStratum(name = "initial 2")
      val subzoneId3 = insertSubstratum(name = "sub 3")

      store.updatePlantingZone(zoneId1) { it.copy(name = "renamed") }

      assertEquals(
          mapOf(
              subzoneId1 to "renamed-sub 1",
              subzoneId2 to "renamed-sub 2",
              subzoneId3 to "initial 2-sub 3",
          ),
          substrataDao.findAll().associate { it.id to it.fullName },
      )
    }

    @Test
    fun `updates zone name in current history entry`() {
      insertPlantingSite(x = 0)
      val plantingZoneId = insertStratum(name = "initial")
      insertSubstratum(name = "subzone")

      clock.instant = Instant.ofEpochSecond(1000)

      insertPlantingSiteHistory(createdTime = clock.instant)
      val newPlantingZoneHistoryId = insertStratumHistory()
      val newPlantingSubzoneHistoryId = insertSubstratumHistory()

      val expectedSiteHistory = dslContext.fetch(PLANTING_SITE_HISTORIES)
      val expectedZoneHistory =
          dslContext.fetch(STRATUM_HISTORIES).onEach { record ->
            if (record.id == newPlantingZoneHistoryId) {
              record.name = "renamed"
            }
          }
      val expectedSubzoneHistory =
          dslContext.fetch(SUBSTRATUM_HISTORIES).onEach { record ->
            if (record.id == newPlantingSubzoneHistoryId) {
              record.fullName = "renamed-subzone"
            }
          }

      store.updatePlantingZone(plantingZoneId) { it.copy(name = "renamed") }

      assertTableEquals(expectedSiteHistory, "Planting site histories should not be affected")
      assertTableEquals(expectedZoneHistory)
      assertTableEquals(expectedSubzoneHistory)
    }

    @Test
    fun `throws exception if no permission`() {
      insertPlantingSite()
      val plantingZoneId = insertStratum()

      every { user.canUpdatePlantingZone(plantingZoneId) } returns false

      assertThrows<AccessDeniedException> { store.updatePlantingZone(plantingZoneId) { it } }
    }
  }
}
