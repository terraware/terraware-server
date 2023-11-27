package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.OrganizationTimeZoneChangedEvent
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.PlantingSiteInUseException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.util.toInstant
import io.mockk.every
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

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
        plantingSeasonsDao,
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
  inner class DeletePlantingSite {
    private val plantingSiteId = PlantingSiteId(1)

    @BeforeEach
    fun setUp() {
      every { user.canDeletePlantingSite(any()) } returns true
    }

    @Test
    fun `throws exception when no permission to delete a planting site`() {
      insertOrganization()
      insertPlantingSite(id = plantingSiteId)
      every { user.canDeletePlantingSite(any()) } returns false

      assertThrows<AccessDeniedException> { service.deletePlantingSite(plantingSiteId) }
    }

    @Test
    fun `throws site in use exception when there are plantings`() {
      insertOrganization()
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite(id = plantingSiteId)
      insertWithdrawal()
      insertDelivery()
      insertPlanting()

      assertThrows<PlantingSiteInUseException> { service.deletePlantingSite(plantingSiteId) }
    }

    @Test
    fun `deletes planting site when site has no plantings and user has permission`() {
      insertOrganization()
      insertPlantingSite(id = plantingSiteId)

      service.deletePlantingSite(plantingSiteId)

      assertThrows<PlantingSiteNotFoundException> {
        plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
      }
    }
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

      service.on(OrganizationTimeZoneChangedEvent(organizationId, oldTimeZone, newTimeZone))

      eventPublisher.assertExactEventsPublished(
          setOf(
              PlantingSiteTimeZoneChangedEvent(
                  plantingSiteWithoutTimeZone1, oldTimeZone, newTimeZone),
              PlantingSiteTimeZoneChangedEvent(
                  plantingSiteWithoutTimeZone2, oldTimeZone, newTimeZone)))

      assertIsEventListener<OrganizationTimeZoneChangedEvent>(service)
    }

    @Test
    fun `updates planting seasons when planting site time zone changes`() {
      val oldTimeZone = insertTimeZone("America/New_York")
      val newTimeZone = insertTimeZone("Europe/Paris")
      val startDate = LocalDate.EPOCH.plusMonths(1)
      val endDate = startDate.plusMonths(3)

      insertOrganization(timeZone = oldTimeZone)
      insertPlantingSite(timeZone = oldTimeZone)
      insertPlantingSeason(timeZone = oldTimeZone, startDate = startDate, endDate = endDate)

      val oldSiteModel =
          plantingSiteStore.fetchSiteById(inserted.plantingSiteId, PlantingSiteDepth.Site)

      service.on(PlantingSiteTimeZoneChangedEvent(oldSiteModel, oldTimeZone, newTimeZone))

      val newSiteModel =
          plantingSiteStore.fetchSiteById(inserted.plantingSiteId, PlantingSiteDepth.Site)

      assertEquals(
          startDate.toInstant(newTimeZone),
          newSiteModel.plantingSeasons.first().startTime,
          "Start time")
      assertEquals(
          endDate.plusDays(1).toInstant(newTimeZone),
          newSiteModel.plantingSeasons.first().endTime,
          "End time")

      assertIsEventListener<PlantingSiteTimeZoneChangedEvent>(service)
    }
  }
}
