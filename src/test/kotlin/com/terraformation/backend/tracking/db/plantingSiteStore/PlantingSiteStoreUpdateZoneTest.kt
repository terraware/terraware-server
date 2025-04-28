package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.multiPolygon
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
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
      val plantingSiteId = insertPlantingSite()

      val initialRow =
          PlantingZonesRow(
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

      plantingZonesDao.insert(initialRow)
      val plantingZoneId = initialRow.id!!

      val newErrorMargin = BigDecimal(10)
      val newStudentsT = BigDecimal(11)
      val newVariance = BigDecimal(12)
      val newPermanent = 13
      val newTemporary = 14
      val newTargetPlantingDensity = BigDecimal(13)

      val expected =
          initialRow.copy(
              errorMargin = newErrorMargin,
              modifiedBy = user.userId,
              modifiedTime = clock.instant(),
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
            name = "bogus",
        )
      }

      assertEquals(expected, plantingZonesDao.fetchOneById(plantingZoneId))
    }

    @Test
    fun `throws exception if no permission`() {
      insertPlantingSite()
      val plantingZoneId = insertPlantingZone()

      every { user.canUpdatePlantingZone(plantingZoneId) } returns false

      assertThrows<AccessDeniedException> { store.updatePlantingZone(plantingZoneId) { it } }
    }
  }
}
