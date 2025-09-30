package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.OrganizationTimeZoneChangedEvent
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.PlantingSiteInUseException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.tracking.tables.records.PlantingZonePopulationsRecord
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.db.DeliveryStore
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.event.PlantingSiteMapEditedEvent
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.ReplacementResult
import com.terraformation.backend.util.toInstant
import io.mockk.every
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class PlantingSiteServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val parentStore by lazy { ParentStore(dslContext) }
  private val deliveryStore by lazy {
    DeliveryStore(clock, deliveriesDao, dslContext, parentStore, plantingsDao)
  }
  private val plantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        TestSingletons.countryDetector,
        dslContext,
        eventPublisher,
        IdentifierGenerator(clock, dslContext),
        monitoringPlotsDao,
        parentStore,
        plantingSeasonsDao,
        plantingSitesDao,
        plantingSubzonesDao,
        plantingZonesDao,
        eventPublisher,
    )
  }
  private val service by lazy {
    PlantingSiteService(deliveryStore, eventPublisher, plantingSiteStore)
  }

  @BeforeEach
  fun setUp() {
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
  }

  @Nested
  inner class DeletePlantingSite {
    @BeforeEach
    fun setUp() {
      every { user.canDeletePlantingSite(any()) } returns true
    }

    @Test
    fun `throws exception when no permission to delete a planting site`() {
      insertOrganization()
      val plantingSiteId = insertPlantingSite()
      every { user.canDeletePlantingSite(any()) } returns false

      assertThrows<AccessDeniedException> { service.deletePlantingSite(plantingSiteId) }
    }

    @Test
    fun `throws site in use exception when there are plantings`() {
      insertOrganization()
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      val plantingSiteId = insertPlantingSite()
      insertNurseryWithdrawal()
      insertDelivery()
      insertPlanting()

      assertThrows<PlantingSiteInUseException> { service.deletePlantingSite(plantingSiteId) }
    }

    @Test
    fun `deletes planting site when site has no plantings and user has permission`() {
      insertOrganization()
      val plantingSiteId = insertPlantingSite()

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
      val oldTimeZone = ZoneId.of("America/New_York")
      val newTimeZone = ZoneId.of("America/Buenos_Aires")

      val organizationId = insertOrganization(timeZone = oldTimeZone)
      insertPlantingSite(timeZone = oldTimeZone)
      val plantingSiteWithoutTimeZone1 =
          plantingSiteStore.fetchSiteById(insertPlantingSite(), PlantingSiteDepth.Site)
      val plantingSiteWithoutTimeZone2 =
          plantingSiteStore.fetchSiteById(insertPlantingSite(), PlantingSiteDepth.Site)

      organizationsDao.update(
          organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = newTimeZone)
      )

      service.on(OrganizationTimeZoneChangedEvent(organizationId, oldTimeZone, newTimeZone))

      eventPublisher.assertExactEventsPublished(
          setOf(
              PlantingSiteTimeZoneChangedEvent(
                  plantingSiteWithoutTimeZone1,
                  oldTimeZone,
                  newTimeZone,
              ),
              PlantingSiteTimeZoneChangedEvent(
                  plantingSiteWithoutTimeZone2,
                  oldTimeZone,
                  newTimeZone,
              ),
          )
      )

      assertIsEventListener<OrganizationTimeZoneChangedEvent>(service)
    }

    @Test
    fun `updates planting seasons when planting site time zone changes`() {
      val oldTimeZone = ZoneId.of("America/New_York")
      val newTimeZone = ZoneId.of("Europe/Paris")
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
          "Start time",
      )
      assertEquals(
          endDate.plusDays(1).toInstant(newTimeZone),
          newSiteModel.plantingSeasons.first().endTime,
          "End time",
      )

      assertIsEventListener<PlantingSiteTimeZoneChangedEvent>(service)
    }
  }

  @Nested
  inner class OnPlantingSiteMapEdited {
    @Test
    fun `recalculates zone-level planting site populations`() {
      insertOrganization()
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val plantingSiteId = insertPlantingSite()
      val plantingZoneId1 = insertPlantingZone()
      val plantingSubzoneId11 = insertPlantingSubzone()
      val plantingSubzoneId12 = insertPlantingSubzone()
      val plantingZoneId2 = insertPlantingZone()
      val plantingSubzoneId21 = insertPlantingSubzone()

      insertPlantingSubzonePopulation(plantingSubzoneId11, speciesId1, 10, 1)
      insertPlantingSubzonePopulation(plantingSubzoneId12, speciesId1, 20, 2)
      insertPlantingSubzonePopulation(plantingSubzoneId12, speciesId2, 40, 4)
      insertPlantingSubzonePopulation(plantingSubzoneId21, speciesId1, 80, 8)

      // Zone populations should be completely replaced
      insertPlantingZonePopulation(plantingZoneId1, speciesId1, 160, 16)
      insertPlantingZonePopulation(plantingZoneId2, speciesId2, 320, 32)

      val site = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Subzone)

      val existingSubzonePopulations = dslContext.selectFrom(PLANTING_SUBZONE_POPULATIONS).fetch()

      service.on(
          PlantingSiteMapEditedEvent(
              site,
              PlantingSiteEdit(BigDecimal.ZERO, site, site, emptyList()),
              ReplacementResult(emptySet(), emptySet()),
          )
      )

      assertTableEquals(existingSubzonePopulations, "Subzone populations should not have changed")

      assertTableEquals(
          listOf(
              PlantingZonePopulationsRecord(
                  plantingZoneId = plantingZoneId1,
                  plantsSinceLastObservation = 3,
                  speciesId = speciesId1,
                  totalPlants = 30,
              ),
              PlantingZonePopulationsRecord(
                  plantingZoneId = plantingZoneId1,
                  plantsSinceLastObservation = 4,
                  speciesId = speciesId2,
                  totalPlants = 40,
              ),
              PlantingZonePopulationsRecord(
                  plantingZoneId = plantingZoneId2,
                  plantsSinceLastObservation = 8,
                  speciesId = speciesId1,
                  totalPlants = 80,
              ),
          )
      )

      assertIsEventListener<PlantingSiteMapEditedEvent>(service)
    }
  }
}
