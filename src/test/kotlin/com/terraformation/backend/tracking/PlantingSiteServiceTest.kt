package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.OrganizationTimeZoneChangedEvent
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlantingSiteServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val eventPublisher = TestEventPublisher()
  private val plantingSiteStore by lazy {
    PlantingSiteStore(
        TestClock(),
        dslContext,
        eventPublisher,
        monitoringPlotsDao,
        ParentStore(dslContext),
        plantingSitesDao,
        plantingSubzonesDao,
        plantingZonesDao)
  }
  private val service by lazy { PlantingSiteService(eventPublisher, plantingSiteStore) }

  @BeforeEach
  fun setUp() {
    insertUser()

    every { user.canReadOrganization(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
  }

  @Nested
  inner class OnTimeZoneChange {
    @Test
    fun `publishes PlantingSiteTimeZoneChangedEvent when organization time zone changes`() {
      val oldTimeZone = insertTimeZone("America/New_York")
      val newTimeZone = insertTimeZone("America/Buenos_Aires")

      insertOrganization(timeZone = oldTimeZone)
      insertPlantingSite(timeZone = oldTimeZone)
      val plantingSiteWithoutTimeZone1 =
          plantingSiteStore.fetchSiteById(insertPlantingSite(), PlantingSiteDepth.Site)
      val plantingSiteWithoutTimeZone2 =
          plantingSiteStore.fetchSiteById(insertPlantingSite(), PlantingSiteDepth.Site)

      organizationsDao.update(
          organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = newTimeZone))

      service.on(OrganizationTimeZoneChangedEvent(organizationId))

      eventPublisher.assertExactEventsPublished(
          setOf(
              PlantingSiteTimeZoneChangedEvent(plantingSiteWithoutTimeZone1),
              PlantingSiteTimeZoneChangedEvent(plantingSiteWithoutTimeZone2)))
    }
  }
}
