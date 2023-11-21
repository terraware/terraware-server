package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.mockUser
import java.time.LocalDate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException

class TrackingIntegrityConstraintsTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  @Test
  fun `only one planting season can be active at a time`() {
    insertSiteData()
    insertPlantingSite()
    insertPlantingSeason(
        startDate = LocalDate.EPOCH, endDate = LocalDate.EPOCH.plusMonths(2), isActive = true)

    assertThrows<DataAccessException> {
      insertPlantingSeason(
          startDate = LocalDate.EPOCH.plusMonths(3),
          endDate = LocalDate.EPOCH.plusMonths(5),
          isActive = true)
    }
  }
}
