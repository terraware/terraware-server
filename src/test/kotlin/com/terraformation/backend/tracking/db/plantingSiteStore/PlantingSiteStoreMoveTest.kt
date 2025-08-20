package com.terraformation.backend.tracking.db.plantingSiteStore

import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreMoveTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class MovePlantingSite {
    @Test
    fun `updates organization ID`() {
      val otherOrganizationId = insertOrganization()
      val plantingSiteId = insertPlantingSite()
      val before = plantingSitesDao.fetchOneById(plantingSiteId)!!
      val newTime = Instant.ofEpochSecond(1000)

      clock.instant = newTime

      store.movePlantingSite(plantingSiteId, otherOrganizationId)

      assertEquals(
          before.copy(
              modifiedTime = newTime,
              organizationId = otherOrganizationId,
          ),
          plantingSitesDao.fetchOneById(plantingSiteId),
      )
    }

    @Test
    fun `throws exception if no permission`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canMovePlantingSiteToAnyOrg(any()) } returns false

      assertThrows<AccessDeniedException> { store.movePlantingSite(plantingSiteId, organizationId) }
    }
  }
}
