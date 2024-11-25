package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus.Dead
import com.terraformation.backend.db.tracking.RecordedPlantStatus.Existing
import com.terraformation.backend.db.tracking.RecordedPlantStatus.Live
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Known
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Other
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Unknown
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotConditionsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSiteSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedZoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.records.ObservedPlotSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedSiteSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedZoneSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedPlantsRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.db.ObservationTestHelper.ObservationPlot
import com.terraformation.backend.tracking.db.ObservationTestHelper.ObservationZone
import com.terraformation.backend.tracking.db.ObservationTestHelper.PlantTotals
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NewObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.ObservationPlotCounts
import com.terraformation.backend.tracking.model.ObservationPlotModel
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

class ObservationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val store: ObservationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        observationRequestedSubzonesDao,
        ParentStore(dslContext),
        recordedPlantsDao)
  }
  private val helper: ObservationTestHelper by lazy {
    ObservationTestHelper(this, store, user.userId)
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    plantingSiteId = insertPlantingSite(x = 0)

    every { user.canCreateObservation(any()) } returns true
    every { user.canManageObservation(any()) } returns true
    every { user.canReadObservation(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canUpdateObservation(any()) } returns true
  }

  @Nested
  inner class FetchObservationsByPlantingSite {
    @Test
    fun `returns observations in date order`() {
      val startDate1 = LocalDate.of(2021, 4, 1)
      val startDate2 = LocalDate.of(2022, 3, 1)
      val endDate1 = LocalDate.of(2021, 4, 30)
      val endDate2 = LocalDate.of(2022, 3, 31)

      // Insert in reverse time order
      val observationId1 =
          insertObservation(
              endDate = endDate2, startDate = startDate2, state = ObservationState.Upcoming)

      val observationId2 = insertObservation(endDate = endDate1, startDate = startDate1)
      insertPlantingZone()
      val subzoneId = insertPlantingSubzone()
      insertMonitoringPlot()
      insertObservationPlot()
      insertObservationRequestedSubzone()

      // Observation in a different planting site
      insertPlantingSite()
      insertObservation()

      val expected =
          listOf(
              ExistingObservationModel(
                  endDate = endDate1,
                  id = observationId2,
                  plantingSiteId = plantingSiteId,
                  requestedSubzoneIds = setOf(subzoneId),
                  startDate = startDate1,
                  state = ObservationState.InProgress,
              ),
              ExistingObservationModel(
                  endDate = endDate2,
                  id = observationId1,
                  plantingSiteId = plantingSiteId,
                  startDate = startDate2,
                  state = ObservationState.Upcoming,
              ),
          )

      val actual = store.fetchObservationsByPlantingSite(plantingSiteId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to read planting site`() {
      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> {
        store.fetchObservationsByPlantingSite(plantingSiteId)
      }
    }
  }

  @Nested
  inner class FetchObservationPlotDetails {
    @Test
    fun `calculates correct values from related tables`() {
      val userId1 = insertUser(firstName = "First", lastName = "Person")
      val userId2 = insertUser(firstName = "Second", lastName = "Human")

      insertPlantingZone(name = "Z1")
      val plantingSubzoneId1 = insertPlantingSubzone(fullName = "Z1-S1", name = "S1")

      // A plot that was observed previously and again in this observation
      val monitoringPlotId11 =
          insertMonitoringPlot(boundary = polygon(1), fullName = "Z1-S1-1", name = "1")
      insertObservation()
      insertObservationPlot()
      val observationId = insertObservation()
      insertObservationPlot(isPermanent = true)

      // This plot is claimed
      val monitoringPlotId12 =
          insertMonitoringPlot(boundary = polygon(2), fullName = "Z1-S1-2", name = "2")
      val claimedTime12 = Instant.ofEpochSecond(12)
      insertObservationPlot(
          ObservationPlotsRow(
              claimedBy = userId1,
              claimedTime = claimedTime12,
              statusId = ObservationPlotStatus.Claimed))

      val plantingSubzoneId2 = insertPlantingSubzone(fullName = "Z1-S2", name = "S2")

      // This plot is claimed and completed
      val monitoringPlotId21 =
          insertMonitoringPlot(boundary = polygon(3), fullName = "Z1-S2-1", name = "1")
      val claimedTime21 = Instant.ofEpochSecond(210)
      val completedTime21 = Instant.ofEpochSecond(211)
      val observedTime21 = Instant.ofEpochSecond(212)
      insertObservationPlot(
          ObservationPlotsRow(
              claimedBy = userId2,
              claimedTime = claimedTime21,
              completedBy = userId1,
              completedTime = completedTime21,
              notes = "Some notes",
              observedTime = observedTime21,
              statusId = ObservationPlotStatus.Completed,
          ))

      val expected =
          listOf(
              AssignedPlotDetails(
                  model =
                      ObservationPlotModel(
                          isPermanent = true,
                          monitoringPlotId = monitoringPlotId11,
                          observationId = observationId,
                      ),
                  boundary = polygon(1),
                  claimedByName = null,
                  completedByName = null,
                  isFirstObservation = false,
                  plantingSubzoneId = plantingSubzoneId1,
                  plantingSubzoneName = "Z1-S1",
                  plotName = "Z1-S1-1",
                  sizeMeters = 30,
              ),
              AssignedPlotDetails(
                  model =
                      ObservationPlotModel(
                          claimedBy = userId1,
                          claimedTime = claimedTime12,
                          isPermanent = false,
                          monitoringPlotId = monitoringPlotId12,
                          observationId = observationId,
                      ),
                  boundary = polygon(2),
                  claimedByName = "First Person",
                  completedByName = null,
                  isFirstObservation = true,
                  plantingSubzoneId = plantingSubzoneId1,
                  plantingSubzoneName = "Z1-S1",
                  plotName = "Z1-S1-2",
                  sizeMeters = 30,
              ),
              AssignedPlotDetails(
                  model =
                      ObservationPlotModel(
                          claimedBy = userId2,
                          claimedTime = claimedTime21,
                          completedBy = userId1,
                          completedTime = completedTime21,
                          isPermanent = false,
                          monitoringPlotId = monitoringPlotId21,
                          notes = "Some notes",
                          observationId = observationId,
                          observedTime = observedTime21,
                      ),
                  boundary = polygon(3),
                  claimedByName = "Second Human",
                  completedByName = "First Person",
                  isFirstObservation = true,
                  plantingSubzoneId = plantingSubzoneId2,
                  plantingSubzoneName = "Z1-S2",
                  plotName = "Z1-S2-1",
                  sizeMeters = 30,
              ))

      val actual = store.fetchObservationPlotDetails(observationId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to read observation`() {
      every { user.canReadObservation(any()) } returns false

      val observationId = insertObservation()

      assertThrows<ObservationNotFoundException> {
        store.fetchObservationPlotDetails(observationId)
      }
    }
  }

  @Nested
  inner class FetchStartableObservations {
    @BeforeEach
    fun setUp() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
    }

    @Test
    fun `only returns observations whose planting sites have plants`() {
      val timeZone = ZoneId.of("America/Denver")
      val startDate = LocalDate.of(2023, 4, 1)
      val endDate = LocalDate.of(2023, 4, 30)

      val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
      clock.instant = now

      helper.insertPlantedSite(timeZone = timeZone)
      val startableObservationId =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      // Another planting site with no plants.
      insertPlantingSite(timeZone = timeZone)
      insertPlantingZone()
      insertPlantingSubzone()
      insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      val expected = setOf(startableObservationId)
      val actual = store.fetchStartableObservations().map { it.id }.toSet()

      assertEquals(expected, actual)
    }

    @Test
    fun `honors planting site time zones`() {
      // Three adjacent time zones, 1 hour apart
      val zone1 = ZoneId.of("America/Los_Angeles")
      val zone2 = ZoneId.of("America/Denver")
      val zone3 = ZoneId.of("America/Chicago")

      val startDate = LocalDate.of(2023, 4, 1)
      val endDate = LocalDate.of(2023, 4, 30)

      // Current time in zone 1: 2023-03-31 23:00:00
      // Current time in zone 2: 2023-04-01 00:00:00
      // Current time in zone 3: 2023-04-01 01:00:00
      val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, zone2).toInstant()
      clock.instant = now

      organizationsDao.update(
          organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = zone2))

      // Start date is now at a site that inherits its time zone from its organization.
      helper.insertPlantedSite(timeZone = null)
      val observationId1 =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      // Start date is an hour ago.
      helper.insertPlantedSite(timeZone = zone3)
      val observationId2 =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      // Start date hasn't arrived yet in the site's time zone.
      helper.insertPlantedSite(timeZone = zone1)
      insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      // Observation already in progress; shouldn't be started
      helper.insertPlantedSite(timeZone = zone3)
      insertObservation(
          endDate = endDate, startDate = startDate, state = ObservationState.InProgress)

      // Start date is still in the future.
      helper.insertPlantedSite(timeZone = zone3)
      insertObservation(
          endDate = endDate, startDate = startDate.plusDays(1), state = ObservationState.Upcoming)

      val expected = setOf(observationId1, observationId2)
      val actual = store.fetchStartableObservations().map { it.id }.toSet()

      assertEquals(expected, actual)
    }

    @Test
    fun `limits results to requested planting site`() {
      val timeZone = ZoneId.of("America/Denver")

      val startDate = LocalDate.of(2023, 4, 1)
      val endDate = LocalDate.of(2023, 4, 30)

      val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
      clock.instant = now

      val plantingSiteId = helper.insertPlantedSite(timeZone = timeZone)
      val observationId =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      insertPlantingSite(timeZone = timeZone)
      insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      val expected = setOf(observationId)
      val actual = store.fetchStartableObservations(plantingSiteId).map { it.id }.toSet()

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class FetchNonNotifiedUpcomingObservations {
    @BeforeEach
    fun setUp() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
    }

    @Test
    fun `does not return observations whose notifications have been sent already`() {
      val timeZone = ZoneId.of("America/Denver")
      val startDate = LocalDate.of(2023, 4, 1)
      val endDate = LocalDate.of(2023, 4, 30)

      val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
      clock.instant = now

      helper.insertPlantedSite(timeZone = timeZone)
      insertObservation(
          endDate = endDate,
          startDate = startDate,
          state = ObservationState.Upcoming,
          upcomingNotificationSentTime = now,
      )

      val actual = store.fetchNonNotifiedUpcomingObservations()

      assertEquals(emptyList<ExistingObservationModel>(), actual)
    }

    @Test
    fun `does not returns observations whose planting sites have no planted subzones`() {
      val timeZone = ZoneId.of("America/Denver")
      val startDate = LocalDate.of(2023, 4, 1)
      val endDate = LocalDate.of(2023, 4, 30)

      val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
      clock.instant = now

      insertPlantingSite(timeZone = timeZone)
      insertPlantingZone()
      insertPlantingSubzone()
      insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      val actual = store.fetchNonNotifiedUpcomingObservations()

      assertEquals(emptyList<ExistingObservationModel>(), actual)
    }

    @Test
    fun `only returns observations whose planting sites have plants`() {
      val timeZone = ZoneId.of("America/Denver")
      val startDate = LocalDate.of(2023, 4, 1)
      val endDate = LocalDate.of(2023, 4, 30)

      val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
      clock.instant = now

      helper.insertPlantedSite(timeZone = timeZone)
      val startableObservationId =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      // Another planting site with no plants.
      insertPlantingSite(timeZone = timeZone)
      insertPlantingZone()
      insertPlantingSubzone()
      insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      val expected = setOf(startableObservationId)
      val actual = store.fetchNonNotifiedUpcomingObservations().map { it.id }.toSet()

      assertEquals(expected, actual)
    }

    @Test
    fun `honors planting site time zones`() {
      // Three adjacent time zones, 1 hour apart
      val zone1 = ZoneId.of("America/Los_Angeles")
      val zone2 = ZoneId.of("America/Denver")
      val zone3 = ZoneId.of("America/Chicago")

      val startDate = LocalDate.of(2023, 4, 1)
      val endDate = LocalDate.of(2023, 4, 30)

      // Current time in zone 1: 2023-02-28 23:00:00
      // Current time in zone 2: 2023-03-01 00:00:00
      // Current time in zone 3: 2023-03-01 01:00:00
      val now = ZonedDateTime.of(startDate.minusMonths(1), LocalTime.MIDNIGHT, zone2).toInstant()
      clock.instant = now

      organizationsDao.update(
          organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = zone2))

      // Start date is a month from now at a site that inherits its time zone from its organization.
      helper.insertPlantedSite(timeZone = null)
      val observationId1 =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      // Start date plus 1 month is an hour ago.
      helper.insertPlantedSite(timeZone = zone3)
      val observationId2 =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      // Start date plus 1 month hasn't arrived yet in the site's time zone.
      helper.insertPlantedSite(timeZone = zone1)
      insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      val expected = setOf(observationId1, observationId2)
      val actual = store.fetchNonNotifiedUpcomingObservations().map { it.id }.toSet()

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class FetchObservationsPastEndDate {
    @Test
    fun `honors planting site time zones`() {
      // Three adjacent time zones, 1 hour apart
      val zone1 = ZoneId.of("America/Los_Angeles")
      val zone2 = ZoneId.of("America/Denver")
      val zone3 = ZoneId.of("America/Chicago")

      val startDate = LocalDate.of(2023, 3, 1)
      val endDate = LocalDate.of(2023, 3, 31)

      // Current time in zone 1: 2023-03-31 23:00:00
      // Current time in zone 2: 2023-04-01 00:00:00
      // Current time in zone 3: 2023-04-01 01:00:00
      val now = ZonedDateTime.of(endDate.plusDays(1), LocalTime.MIDNIGHT, zone2).toInstant()
      clock.instant = now

      organizationsDao.update(
          organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = zone2))

      // End date ending now at a site that inherits its time zone from its organization.
      insertPlantingSite()
      val observationId1 =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.InProgress)

      // End date ended an hour ago.
      insertPlantingSite(timeZone = zone3)
      val observationId2 =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.InProgress)

      // End date isn't over yet in the site's time zone.
      insertPlantingSite(timeZone = zone1)
      insertObservation(
          endDate = endDate, startDate = startDate, state = ObservationState.InProgress)

      // Observation already completed; shouldn't be marked as overdue
      insertPlantingSite(timeZone = zone3)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH),
          endDate = endDate,
          startDate = startDate,
          state = ObservationState.Completed)

      // End date is still in the future.
      insertPlantingSite(timeZone = zone3)
      insertObservation(
          endDate = endDate.plusDays(1), startDate = startDate, state = ObservationState.InProgress)

      val expected = setOf(observationId1, observationId2)
      val actual = store.fetchObservationsPastEndDate().map { it.id }.toSet()

      assertEquals(expected, actual)
    }

    @Test
    fun `limits results to requested planting site`() {
      val timeZone = ZoneId.of("America/Denver")

      val startDate = LocalDate.of(2023, 4, 1)
      val endDate = LocalDate.of(2023, 4, 30)

      val now = ZonedDateTime.of(endDate.plusDays(1), LocalTime.MIDNIGHT, timeZone).toInstant()
      clock.instant = now

      val plantingSiteId = insertPlantingSite(timeZone = timeZone)
      val observationId =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.InProgress)

      insertPlantingSite(timeZone = timeZone)
      insertObservation(
          endDate = endDate, startDate = startDate, state = ObservationState.InProgress)

      val expected = setOf(observationId)
      val actual = store.fetchObservationsPastEndDate(plantingSiteId).map { it.id }.toSet()

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class CreateObservation {
    @Test
    fun `saves fields that are relevant to a new observation`() {
      insertPlantingZone()
      val subzoneId1 = insertPlantingSubzone()
      val subzoneId2 = insertPlantingSubzone()
      insertPlantingSubzone() // Should not be included in observation

      val observationId =
          store.createObservation(
              NewObservationModel(
                  completedTime = Instant.EPOCH,
                  endDate = LocalDate.of(2020, 1, 31),
                  id = null,
                  plantingSiteId = plantingSiteId,
                  requestedSubzoneIds = setOf(subzoneId1, subzoneId2),
                  startDate = LocalDate.of(2020, 1, 1),
                  state = ObservationState.Completed,
              ))

      val expected =
          ObservationsRow(
              // Completed time should not be saved
              createdTime = clock.instant(),
              endDate = LocalDate.of(2020, 1, 31),
              id = observationId,
              plantingSiteId = plantingSiteId,
              startDate = LocalDate.of(2020, 1, 1),
              stateId = ObservationState.Upcoming,
          )

      val actual = observationsDao.fetchOneById(observationId)

      assertEquals(expected, actual)

      assertEquals(
          setOf(subzoneId1, subzoneId2),
          observationRequestedSubzonesDao.findAll().map { it.plantingSubzoneId }.toSet(),
          "Subzone IDs")
    }

    @Test
    fun `throws exception if requested subzone is not in correct site`() {
      insertPlantingSite()
      insertPlantingZone()
      val otherSiteSubzoneId = insertPlantingSubzone()

      assertThrows<PlantingSubzoneNotFoundException> {
        store.createObservation(
            NewObservationModel(
                endDate = LocalDate.of(2020, 1, 31),
                id = null,
                plantingSiteId = plantingSiteId,
                requestedSubzoneIds = setOf(otherSiteSubzoneId),
                startDate = LocalDate.of(2020, 1, 1),
                state = ObservationState.Upcoming,
            ))
      }
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canCreateObservation(plantingSiteId) } returns false

      assertThrows<AccessDeniedException> {
        store.createObservation(
            NewObservationModel(
                endDate = LocalDate.EPOCH,
                id = null,
                plantingSiteId = plantingSiteId,
                startDate = LocalDate.EPOCH,
                state = ObservationState.Upcoming,
            ))
      }
    }
  }

  @Nested
  inner class HasPlots {
    @Test
    fun `returns false if observation has no plots`() {
      val observationId = insertObservation()

      assertFalse(store.hasPlots(observationId))
    }

    @Test
    fun `returns true if observation has plots`() {
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      val observationId = insertObservation()
      insertObservationPlot()

      assertTrue(store.hasPlots(observationId))
    }

    @Test
    fun `throws exception if no permission`() {
      val observationId = insertObservation()

      every { user.canReadObservation(observationId) } returns false

      assertThrows<ObservationNotFoundException> { store.hasPlots(observationId) }
    }

    @Test
    fun `throws exception if observation does not exist`() {
      assertThrows<ObservationNotFoundException> { store.hasPlots(ObservationId(1)) }
    }
  }

  @Nested
  inner class UpdateObservationState {
    @Test
    fun `updates state from InProgress to Completed if user has update permission`() {
      val observationId = insertObservation()
      val initial = store.fetchObservationById(observationId)

      every { user.canManageObservation(observationId) } returns false

      store.updateObservationState(observationId, ObservationState.Completed)

      assertEquals(
          initial.copy(completedTime = clock.instant(), state = ObservationState.Completed),
          store.fetchObservationById(observationId))
    }

    @Test
    fun `updates state from Upcoming to InProgress if user has manage permission`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)
      val initial = store.fetchObservationById(observationId)

      store.updateObservationState(observationId, ObservationState.InProgress)

      assertEquals(
          initial.copy(state = ObservationState.InProgress),
          store.fetchObservationById(observationId))
    }

    @Test
    fun `throws exception if no permission to update to Completed`() {
      val observationId = insertObservation()

      every { user.canManageObservation(observationId) } returns false
      every { user.canUpdateObservation(observationId) } returns false

      assertThrows<AccessDeniedException> {
        store.updateObservationState(observationId, ObservationState.Completed)
      }
    }

    @Test
    fun `throws exception if no permission to update to InProgress`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)

      every { user.canManageObservation(observationId) } returns false

      assertThrows<AccessDeniedException> {
        store.updateObservationState(observationId, ObservationState.InProgress)
      }
    }

    @Test
    fun `throws exception on illegal state transition`() {
      val observationId = insertObservation(state = ObservationState.InProgress)

      assertThrows<IllegalArgumentException> {
        store.updateObservationState(observationId, ObservationState.Upcoming)
      }
    }
  }

  @Nested
  inner class AbandonObservation {
    @Test
    fun `deletes an observation if no plot has been completed`() {
      val observationId = insertObservation()
      assertNotNull(observationsDao.fetchOneById(observationId), "Before abandon")
      store.abandonObservation(observationId)
      assertNull(observationsDao.fetchOneById(observationId), "After abandon")
    }

    @Test
    fun `sets an observation to Abandoned and incomplete plots to Not Observed and unclaims`() {
      insertPlantingZone()
      insertPlantingSubzone()
      val completedPlotId = insertMonitoringPlot()
      val unclaimedPlotId = insertMonitoringPlot()
      val claimedPlotId = insertMonitoringPlot()

      val observationId = insertObservation()

      insertObservationPlot(
          observationId = observationId,
          monitoringPlotId = completedPlotId,
          claimedBy = currentUser().userId,
          claimedTime = Instant.EPOCH,
          completedBy = currentUser().userId,
          completedTime = Instant.EPOCH,
          statusId = ObservationPlotStatus.Completed)

      insertObservationPlot(
          observationId = observationId,
          monitoringPlotId = unclaimedPlotId,
          statusId = ObservationPlotStatus.Unclaimed)

      insertObservationPlot(
          observationId = observationId,
          monitoringPlotId = claimedPlotId,
          claimedBy = currentUser().userId,
          claimedTime = Instant.EPOCH,
          statusId = ObservationPlotStatus.Claimed)

      val existing = observationsDao.fetchOneById(observationId)!!

      val plotsRows = observationPlotsDao.findAll().associateBy { it.monitoringPlotId }
      val completedRow = plotsRows[completedPlotId]!!
      val unclaimedRow = plotsRows[unclaimedPlotId]!!
      val claimedRow = plotsRows[claimedPlotId]!!

      clock.instant = Instant.ofEpochSecond(500)

      store.abandonObservation(observationId)

      assertEquals(
          setOf(
              completedRow,
              unclaimedRow.copy(statusId = ObservationPlotStatus.NotObserved),
              claimedRow.copy(
                  claimedBy = null,
                  claimedTime = null,
                  statusId = ObservationPlotStatus.NotObserved,
              ),
          ),
          observationPlotsDao.fetchByObservationId(observationId).toSet(),
          "Observation plots after abandoning")

      assertEquals(
          existing.copy(completedTime = clock.instant, stateId = ObservationState.Abandoned),
          observationsDao.fetchOneById(observationId),
          "Observation after abandoning")
    }

    @Test
    fun `throws exception if no permission to update observation`() {
      val observationId = insertObservation()

      every { user.canUpdateObservation(observationId) } returns false

      assertThrows<AccessDeniedException> { store.abandonObservation(observationId) }
    }

    @EnumSource(names = ["Abandoned", "Completed"])
    @ParameterizedTest
    fun `throws exception when abandoning an already ended observation`(state: ObservationState) {
      val observationId = insertObservation(completedTime = Instant.EPOCH, state = state)

      insertPlantingZone()
      insertPlantingSubzone()
      val completedPlot = insertMonitoringPlot()

      insertObservationPlot(
          observationId = observationId,
          monitoringPlotId = completedPlot,
          claimedBy = currentUser().userId,
          claimedTime = Instant.EPOCH,
          completedBy = currentUser().userId,
          completedTime = Instant.EPOCH,
          statusId = ObservationPlotStatus.Completed)

      assertThrows<ObservationAlreadyEndedException> { store.abandonObservation(observationId) }
    }
  }

  @Nested
  inner class MarkUpcomingNotificationComplete {
    @Test
    fun `updates notification sent timestamp`() {
      val observationId = insertObservation()

      clock.instant = Instant.ofEpochSecond(1234)

      store.markUpcomingNotificationComplete(observationId)

      assertEquals(
          clock.instant, observationsDao.fetchOneById(observationId)?.upcomingNotificationSentTime)
    }

    @Test
    fun `throws exception if no permission to manage observation`() {
      val observationId = insertObservation()

      every { user.canManageObservation(observationId) } returns false

      assertThrows<AccessDeniedException> { store.markUpcomingNotificationComplete(observationId) }
    }
  }

  @Nested
  inner class MergeOtherSpecies {
    private lateinit var plantingZoneId: PlantingZoneId
    private lateinit var monitoringPlotId: MonitoringPlotId

    @BeforeEach
    fun setUp() {
      plantingZoneId = insertPlantingZone()
      insertPlantingSubzone()
      monitoringPlotId = insertMonitoringPlot()

      every { user.canUpdateSpecies(any()) } returns true
    }

    @Test
    fun `updates raw recorded plants data`() {
      val gpsCoordinates = point(1)
      val speciesId = insertSpecies()

      val observationId1 = insertObservation()
      insertObservationPlot()
      insertRecordedPlant(speciesName = "Species to merge", gpsCoordinates = gpsCoordinates)
      insertRecordedPlant(speciesName = "Other species", gpsCoordinates = gpsCoordinates)

      val observationId2 = insertObservation()
      insertObservationPlot()
      insertRecordedPlant(speciesName = "Species to merge", gpsCoordinates = gpsCoordinates)

      store.mergeOtherSpecies(observationId1, "Species to merge", speciesId)

      assertTableEquals(
          listOf(
              RecordedPlantsRecord(
                  certaintyId = Known,
                  gpsCoordinates = gpsCoordinates,
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId1,
                  speciesId = speciesId,
                  statusId = Live,
              ),
              RecordedPlantsRecord(
                  certaintyId = Other,
                  gpsCoordinates = gpsCoordinates,
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId1,
                  speciesName = "Other species",
                  statusId = Live,
              ),
              RecordedPlantsRecord(
                  certaintyId = Other,
                  gpsCoordinates = gpsCoordinates,
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId2,
                  speciesName = "Species to merge",
                  statusId = Live,
              ),
          ))
    }

    @Test
    fun `updates observed species totals`() {
      val gpsCoordinates = point(1)
      val speciesId = insertSpecies()

      val observationId1 = insertObservation()
      insertObservationPlot(claimedBy = user.userId, isPermanent = true)

      store.completePlot(
          observationId1,
          monitoringPlotId,
          emptySet(),
          null,
          Instant.EPOCH,
          listOf(
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = gpsCoordinates,
                  speciesId = speciesId,
                  statusId = Live),
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = gpsCoordinates,
                  speciesName = "Merge",
                  statusId = Live),
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = gpsCoordinates,
                  speciesName = "Merge",
                  statusId = Dead),
          ))

      clock.instant = Instant.ofEpochSecond(1)

      val observationId2 = insertObservation()
      insertObservationPlot(claimedBy = user.userId, isPermanent = true)

      store.completePlot(
          observationId2,
          monitoringPlotId,
          emptySet(),
          null,
          Instant.EPOCH,
          listOf(
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = gpsCoordinates,
                  speciesId = speciesId,
                  statusId = Live),
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = gpsCoordinates,
                  speciesName = "Merge",
                  statusId = Live),
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = gpsCoordinates,
                  speciesName = "Merge",
                  statusId = Dead),
          ))

      val expectedPlotsBeforeMerge =
          listOf(
              ObservedPlotSpeciesTotalsRecord(
                  observationId = observationId1,
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId,
                  speciesName = null,
                  certaintyId = Known,
                  totalLive = 1,
                  totalDead = 0,
                  totalExisting = 0,
                  mortalityRate = 0,
                  cumulativeDead = 0,
                  permanentLive = 1,
              ),
              ObservedPlotSpeciesTotalsRecord(
                  observationId = observationId1,
                  monitoringPlotId = monitoringPlotId,
                  speciesId = null,
                  speciesName = "Merge",
                  certaintyId = Other,
                  totalLive = 1,
                  totalDead = 1,
                  totalExisting = 0,
                  mortalityRate = 50,
                  cumulativeDead = 1,
                  permanentLive = 1,
              ),
              ObservedPlotSpeciesTotalsRecord(
                  observationId = observationId2,
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId,
                  speciesName = null,
                  certaintyId = Known,
                  totalLive = 1,
                  totalDead = 0,
                  totalExisting = 0,
                  mortalityRate = 0,
                  cumulativeDead = 0,
                  permanentLive = 1,
              ),
              ObservedPlotSpeciesTotalsRecord(
                  observationId = observationId2,
                  monitoringPlotId = monitoringPlotId,
                  speciesId = null,
                  speciesName = "Merge",
                  certaintyId = Other,
                  totalLive = 1,
                  totalDead = 1,
                  totalExisting = 0,
                  mortalityRate = 67,
                  cumulativeDead = 2,
                  permanentLive = 1,
              ),
          )

      assertTableEquals(expectedPlotsBeforeMerge, "Before merge")
      assertTableEquals(expectedPlotsBeforeMerge.map { it.toZone() }, "Before merge")
      assertTableEquals(expectedPlotsBeforeMerge.map { it.toSite() }, "Before merge")

      store.mergeOtherSpecies(observationId1, "Merge", speciesId)

      val expectedPlotsAfterMerge =
          listOf(
              expectedPlotsBeforeMerge[0].apply {
                totalLive = 2
                totalDead = 1
                cumulativeDead = 1
                permanentLive = 2
                mortalityRate = 33
              },
              // expectedPlotsBeforeMerge[1] should be deleted
              expectedPlotsBeforeMerge[2].apply {
                cumulativeDead = 1
                mortalityRate = 50
              },
              expectedPlotsBeforeMerge[3].apply {
                cumulativeDead = 1
                mortalityRate = 50
              },
          )

      assertTableEquals(expectedPlotsAfterMerge, "After merge")
      assertTableEquals(expectedPlotsAfterMerge.map { it.toZone() }, "After merge")
      assertTableEquals(expectedPlotsAfterMerge.map { it.toSite() }, "After merge")
    }

    @Test
    fun `throws exception if no permission to update observation`() {
      val observationId = insertObservation()
      val speciesId = insertSpecies()

      every { user.canUpdateObservation(observationId) } returns false

      assertThrows<AccessDeniedException> {
        store.mergeOtherSpecies(observationId, "Other", speciesId)
      }
    }

    @Test
    fun `throws exception if no permission to update species`() {
      val observationId = insertObservation()
      val speciesId = insertSpecies()

      every { user.canReadSpecies(speciesId) } returns true
      every { user.canUpdateSpecies(speciesId) } returns false

      assertThrows<AccessDeniedException> {
        store.mergeOtherSpecies(observationId, "Other", speciesId)
      }
    }

    @Test
    fun `throws exception if species is from a different organization`() {
      val observationId = insertObservation()
      insertOrganization()
      val speciesId = insertSpecies()

      assertThrows<SpeciesInWrongOrganizationException> {
        store.mergeOtherSpecies(observationId, "Other", speciesId)
      }
    }

    private fun ObservedPlotSpeciesTotalsRecord.toZone(
        plantingZoneId: PlantingZoneId = inserted.plantingZoneId
    ) =
        ObservedZoneSpeciesTotalsRecord(
            observationId = observationId,
            plantingZoneId = plantingZoneId,
            speciesId = speciesId,
            speciesName = speciesName,
            certaintyId = certaintyId,
            totalLive = totalLive,
            totalDead = totalDead,
            totalExisting = totalExisting,
            mortalityRate = mortalityRate,
            cumulativeDead = cumulativeDead,
            permanentLive = permanentLive)

    private fun ObservedPlotSpeciesTotalsRecord.toSite(
        plantingSiteId: PlantingSiteId = inserted.plantingSiteId
    ) =
        ObservedSiteSpeciesTotalsRecord(
            observationId = observationId,
            plantingSiteId = plantingSiteId,
            speciesId = speciesId,
            speciesName = speciesName,
            certaintyId = certaintyId,
            totalLive = totalLive,
            totalDead = totalDead,
            totalExisting = totalExisting,
            mortalityRate = mortalityRate,
            cumulativeDead = cumulativeDead,
            permanentLive = permanentLive)
  }

  @Nested
  inner class AddPlotsToObservation {
    @Test
    fun `honors isPermanent flag`() {
      insertPlantingZone()
      insertPlantingSubzone()
      val permanentPlotId = insertMonitoringPlot(permanentCluster = 1)
      val temporaryPlotId = insertMonitoringPlot(permanentCluster = 2)
      val observationId = insertObservation()

      store.addPlotsToObservation(observationId, listOf(permanentPlotId), isPermanent = true)
      store.addPlotsToObservation(observationId, listOf(temporaryPlotId), isPermanent = false)

      assertEquals(
          mapOf(permanentPlotId to true, temporaryPlotId to false),
          observationPlotsDao.findAll().associate { it.monitoringPlotId to it.isPermanent })
    }

    @Test
    fun `throws exception if same plot is added twice`() {
      insertPlantingZone()
      insertPlantingSubzone()
      val plotId = insertMonitoringPlot()
      val observationId = insertObservation()

      store.addPlotsToObservation(observationId, listOf(plotId), true)

      assertThrows<DuplicateKeyException> {
        store.addPlotsToObservation(observationId, listOf(plotId), false)
      }
    }

    @Test
    fun `throws exception if plots belong to a different planting site`() {
      val observationId = insertObservation()

      insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      val otherSitePlotId = insertMonitoringPlot()

      assertThrows<IllegalStateException> {
        store.addPlotsToObservation(observationId, listOf(otherSitePlotId), true)
      }
    }

    @Test
    fun `throws exception if no permission to manage observation`() {
      val observationId = insertObservation()

      every { user.canManageObservation(observationId) } returns false

      assertThrows<AccessDeniedException> {
        store.addPlotsToObservation(observationId, emptyList(), true)
      }
    }
  }

  @Nested
  inner class ClaimPlot {
    private lateinit var observationId: ObservationId
    private lateinit var plotId: MonitoringPlotId

    @BeforeEach
    fun setUp() {
      insertPlantingZone()
      insertPlantingSubzone()
      plotId = insertMonitoringPlot()
      observationId = insertObservation()
    }

    @Test
    fun `claims plot if not claimed by anyone`() {
      insertObservationPlot()

      store.claimPlot(observationId, plotId)

      val row = observationPlotsDao.findAll().first()

      assertEquals(ObservationPlotStatus.Claimed, row.statusId, "Plot status")
      assertEquals(user.userId, row.claimedBy, "Claimed by")
      assertEquals(clock.instant, row.claimedTime, "Claimed time")
    }

    @Test
    fun `updates claim time if plot is reclaimed by current claimant`() {
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          statusId = ObservationPlotStatus.Claimed)

      clock.instant = Instant.ofEpochSecond(2)

      store.claimPlot(observationId, plotId)

      val plotsRow = observationPlotsDao.findAll().first()

      assertEquals(ObservationPlotStatus.Claimed, plotsRow.statusId, "Plot status is unchanged")
      assertEquals(user.userId, plotsRow.claimedBy, "Should remain claimed by user")
      assertEquals(clock.instant, plotsRow.claimedTime, "Claim time should be updated")
    }

    @Test
    fun `throws exception if plot is claimed by someone else`() {
      val otherUserId = insertUser()

      insertObservationPlot(
          claimedBy = otherUserId,
          claimedTime = Instant.EPOCH,
          statusId = ObservationPlotStatus.Claimed)

      assertThrows<PlotAlreadyClaimedException> { store.claimPlot(observationId, plotId) }
    }

    @Test
    fun `throws exception if plot observation status is completed`() {
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          statusId = ObservationPlotStatus.Completed)

      assertThrows<PlotAlreadyCompletedException> { store.claimPlot(observationId, plotId) }
    }

    @Test
    fun `throws exception if plot observation status is not observed`() {
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          statusId = ObservationPlotStatus.NotObserved)

      assertThrows<PlotAlreadyCompletedException> { store.claimPlot(observationId, plotId) }
    }

    @Test
    fun `throws exception if no permission to update observation`() {
      insertObservationPlot()

      every { user.canUpdateObservation(observationId) } returns false

      assertThrows<AccessDeniedException> { store.claimPlot(observationId, plotId) }
    }

    @Test
    fun `throws exception if monitoring plot not assigned to observation`() {
      assertThrows<PlotNotInObservationException> { store.claimPlot(observationId, plotId) }
    }
  }

  @Nested
  inner class ReleasePlot {
    private lateinit var observationId: ObservationId
    private lateinit var plotId: MonitoringPlotId

    @BeforeEach
    fun setUp() {
      insertPlantingZone()
      insertPlantingSubzone()
      plotId = insertMonitoringPlot()
      observationId = insertObservation()
    }

    @Test
    fun `releases claim on plot`() {
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          statusId = ObservationPlotStatus.Claimed)

      store.releasePlot(observationId, plotId)

      val row = observationPlotsDao.findAll().first()

      assertEquals(ObservationPlotStatus.Unclaimed, row.statusId, "Plot status")
      assertNull(row.claimedBy, "Claimed by")
      assertNull(row.claimedTime, "Claimed time")
    }

    @Test
    fun `throws exception if plot is not claimed`() {
      insertObservationPlot()

      assertThrows<PlotNotClaimedException> { store.releasePlot(observationId, plotId) }
    }

    @Test
    fun `throws exception if plot is claimed by someone else`() {
      val otherUserId = insertUser()

      insertObservationPlot(
          claimedBy = otherUserId,
          claimedTime = Instant.EPOCH,
          statusId = ObservationPlotStatus.Claimed)

      assertThrows<PlotAlreadyClaimedException> { store.releasePlot(observationId, plotId) }
    }

    @Test
    fun `throws exception if plot observation status is completed`() {
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          statusId = ObservationPlotStatus.Completed)

      assertThrows<PlotAlreadyCompletedException> { store.releasePlot(observationId, plotId) }
    }

    @Test
    fun `throws exception if plot observation status is not observed`() {
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          statusId = ObservationPlotStatus.NotObserved)

      assertThrows<PlotAlreadyCompletedException> { store.releasePlot(observationId, plotId) }
    }

    @Test
    fun `throws exception if no permission to update observation`() {
      insertObservationPlot()

      every { user.canUpdateObservation(observationId) } returns false

      assertThrows<AccessDeniedException> { store.releasePlot(observationId, plotId) }
    }

    @Test
    fun `throws exception if monitoring plot not assigned to observation`() {
      assertThrows<PlotNotInObservationException> { store.releasePlot(observationId, plotId) }
    }
  }

  @Nested
  inner class CompletePlot {
    private lateinit var observationId: ObservationId
    private lateinit var plotId: MonitoringPlotId

    @BeforeEach
    fun setUp() {
      insertPlantingZone()
      insertPlantingSubzone()
      plotId = insertMonitoringPlot()
      observationId = insertObservation()
    }

    @Test
    fun `records plot data`() {
      val speciesId = insertSpecies()
      insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)
      insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)
      insertObservation()
      insertObservationPlot(
          claimedBy = user.userId, claimedTime = Instant.EPOCH, monitoringPlotId = plotId)

      val initialRows = observationPlotsDao.findAll()

      val observedTime = Instant.ofEpochSecond(1)
      clock.instant = Instant.ofEpochSecond(123)

      val recordedPlants =
          listOf(
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId,
                  statusId = Live,
              ),
              RecordedPlantsRow(
                  certaintyId = Unknown,
                  gpsCoordinates = point(2),
                  statusId = Dead,
              ),
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = point(3),
                  speciesName = "Who knows",
                  statusId = Existing,
              ),
          )

      store.completePlot(
          observationId,
          plotId,
          setOf(ObservableCondition.AnimalDamage, ObservableCondition.FastGrowth),
          "Notes",
          observedTime,
          recordedPlants)

      val expectedConditions =
          setOf(
              ObservationPlotConditionsRow(observationId, plotId, ObservableCondition.AnimalDamage),
              ObservationPlotConditionsRow(observationId, plotId, ObservableCondition.FastGrowth),
          )

      val expectedPlants =
          recordedPlants
              .map { it.copy(monitoringPlotId = plotId, observationId = observationId) }
              .toSet()

      // Verify that only the row for this plot in this observation was updated.
      val expectedRows =
          initialRows
              .map { row ->
                if (row.observationId == observationId && row.monitoringPlotId == plotId) {
                  row.copy(
                      completedBy = user.userId,
                      completedTime = clock.instant,
                      notes = "Notes",
                      observedTime = observedTime,
                      statusId = ObservationPlotStatus.Completed,
                  )
                } else {
                  row
                }
              }
              .toSet()

      assertEquals(expectedConditions, observationPlotConditionsDao.findAll().toSet())
      assertEquals(expectedPlants, recordedPlantsDao.findAll().map { it.copy(id = null) }.toSet())
      assertEquals(expectedRows, observationPlotsDao.findAll().toSet())
    }

    @Test
    fun `updates observed species totals`() {
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val speciesId3 = insertSpecies()
      insertObservationPlot(
          claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)
      val zoneId1 = inserted.plantingZoneId
      val zone1PlotId2 = insertMonitoringPlot()
      insertObservationPlot(
          claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = false)
      val zoneId2 = insertPlantingZone()
      insertPlantingSubzone()
      val zone2PlotId1 = insertMonitoringPlot()
      insertObservationPlot(
          claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)

      // We want to verify that the "plants since last observation" numbers aren't reset until all
      // the plots are completed.
      insertMonitoringPlot()
      insertObservationPlot()
      insertPlantingSitePopulation(totalPlants = 3, plantsSinceLastObservation = 3)
      insertPlantingZonePopulation(totalPlants = 2, plantsSinceLastObservation = 2)
      insertPlantingSubzonePopulation(totalPlants = 1, plantsSinceLastObservation = 1)

      val observedTime = Instant.ofEpochSecond(1)
      clock.instant = Instant.ofEpochSecond(123)

      store.completePlot(
          observationId,
          plotId,
          emptySet(),
          "Notes",
          observedTime,
          listOf(
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = Live,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = Live,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = Dead,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = Existing,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId2,
                  statusId = Dead,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId3,
                  statusId = Existing,
              ),
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = point(1),
                  speciesName = "Other 1",
                  statusId = Live,
              ),
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = point(1),
                  speciesName = "Other 1",
                  statusId = Dead,
              ),
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = point(1),
                  speciesName = "Other 2",
                  statusId = Live,
              ),
              RecordedPlantsRow(
                  certaintyId = Unknown,
                  gpsCoordinates = point(1),
                  statusId = Live,
              ),
          ))

      val zone1Plot1Species1Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              speciesId = speciesId1,
              speciesName = null,
              certaintyId = Known,
              totalLive = 2,
              totalDead = 1,
              totalExisting = 1,
              mortalityRate = 33,
              cumulativeDead = 1,
              permanentLive = 2)
      // Parameter names omitted after this to keep the test method size manageable.
      val zone1Plot1Species2Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, plotId, speciesId2, null, Known, 0, 1, 0, 100, 1, 0)
      val zone1Plot1Species3Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, plotId, speciesId3, null, Known, 0, 0, 1, 0, 0, 0)
      val zone1Plot1Other1Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, plotId, null, "Other 1", Other, 1, 1, 0, 50, 1, 1)
      val zone1Plot1Other2Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, plotId, null, "Other 2", Other, 1, 0, 0, 0, 0, 1)
      val zone1Plot1UnknownTotals =
          ObservedPlotSpeciesTotalsRow(observationId, plotId, null, null, Unknown, 1, 0, 0, 0, 0, 1)
      var siteSpecies1Totals =
          ObservedSiteSpeciesTotalsRow(
              observationId, inserted.plantingSiteId, speciesId1, null, Known, 2, 1, 1, 33, 1, 2)
      val siteSpecies2Totals =
          ObservedSiteSpeciesTotalsRow(
              observationId, inserted.plantingSiteId, speciesId2, null, Known, 0, 1, 0, 100, 1, 0)
      var siteSpecies3Totals =
          ObservedSiteSpeciesTotalsRow(
              observationId, inserted.plantingSiteId, speciesId3, null, Known, 0, 0, 1, 0, 0, 0)
      var siteOther1Totals =
          ObservedSiteSpeciesTotalsRow(
              observationId, plantingSiteId, null, "Other 1", Other, 1, 1, 0, 50, 1, 1)
      val siteOther2Totals =
          ObservedSiteSpeciesTotalsRow(
              observationId, plantingSiteId, null, "Other 2", Other, 1, 0, 0, 0, 0, 1)
      var siteUnknownTotals =
          ObservedSiteSpeciesTotalsRow(
              observationId, plantingSiteId, null, null, Unknown, 1, 0, 0, 0, 0, 1)
      var zone1Species1Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId1, speciesId1, null, Known, 2, 1, 1, 33, 1, 2)
      val zone1Species2Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId1, speciesId2, null, Known, 0, 1, 0, 100, 1, 0)
      var zone1Species3Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId1, speciesId3, null, Known, 0, 0, 1, 0, 0, 0)
      val zone1Other1Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId1, null, "Other 1", Other, 1, 1, 0, 50, 1, 1)
      val zone1Other2Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId1, null, "Other 2", Other, 1, 0, 0, 0, 0, 1)
      var zone1UnknownTotals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId1, null, null, Unknown, 1, 0, 0, 0, 0, 1)

      helper.assertTotals(
          setOf(
              siteOther1Totals,
              siteOther2Totals,
              siteSpecies1Totals,
              siteSpecies2Totals,
              siteSpecies3Totals,
              siteUnknownTotals,
              zone1Other1Totals,
              zone1Other2Totals,
              zone1Plot1Other1Totals,
              zone1Plot1Other2Totals,
              zone1Plot1Species1Totals,
              zone1Plot1Species2Totals,
              zone1Plot1Species3Totals,
              zone1Plot1UnknownTotals,
              zone1Species1Totals,
              zone1Species2Totals,
              zone1Species3Totals,
              zone1UnknownTotals,
          ),
          "Totals after first plot completed")

      store.completePlot(
          observationId,
          zone1PlotId2,
          emptySet(),
          null,
          observedTime,
          listOf(
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = Live),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId3,
                  statusId = Existing,
              ),
              RecordedPlantsRow(
                  certaintyId = Unknown,
                  gpsCoordinates = point(1),
                  statusId = Live,
              ),
          ))

      val zone1Plot2Species1Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, zone1PlotId2, speciesId1, null, Known, 1, 0, 0, null, 0, 0)
      val zone1Plot2Species3Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, zone1PlotId2, speciesId3, null, Known, 0, 0, 1, null, 0, 0)
      val zone1Plot2UnknownTotals =
          ObservedPlotSpeciesTotalsRow(
              observationId, zone1PlotId2, null, null, Unknown, 1, 0, 0, null, 0, 0)
      siteSpecies1Totals = siteSpecies1Totals.copy(totalLive = 3)
      siteSpecies3Totals = siteSpecies3Totals.copy(totalExisting = 2)
      siteUnknownTotals = siteUnknownTotals.copy(totalLive = 2)
      zone1Species1Totals = zone1Species1Totals.copy(totalLive = 3)
      zone1Species3Totals = zone1Species3Totals.copy(totalExisting = 2)
      zone1UnknownTotals = zone1UnknownTotals.copy(totalLive = 2)

      helper.assertTotals(
          setOf(
              siteOther1Totals,
              siteOther2Totals,
              siteSpecies1Totals,
              siteSpecies2Totals,
              siteSpecies3Totals,
              siteUnknownTotals,
              zone1Other1Totals,
              zone1Other2Totals,
              zone1Plot1Other1Totals,
              zone1Plot1Other2Totals,
              zone1Plot1Species1Totals,
              zone1Plot1Species2Totals,
              zone1Plot1Species3Totals,
              zone1Plot1UnknownTotals,
              zone1Plot2Species1Totals,
              zone1Plot2Species3Totals,
              zone1Plot2UnknownTotals,
              zone1Species1Totals,
              zone1Species2Totals,
              zone1Species3Totals,
              zone1UnknownTotals,
          ),
          "Totals after additional live plant recorded")

      store.completePlot(
          observationId,
          zone2PlotId1,
          emptySet(),
          null,
          observedTime,
          listOf(
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = Dead,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = Existing,
              ),
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = point(1),
                  speciesName = "Other 1",
                  statusId = Live)))

      val zone2Plot1Species1Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, zone2PlotId1, speciesId1, null, Known, 0, 1, 1, 100, 1, 0)
      val zone2Plot1Other1Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, zone2PlotId1, null, "Other 1", Other, 1, 0, 0, 0, 0, 1)
      val zone2Species1Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId2, speciesId1, null, Known, 0, 1, 1, 100, 1, 0)
      val zone2Other1Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId2, null, "Other 1", Other, 1, 0, 0, 0, 0, 1)
      siteSpecies1Totals =
          siteSpecies1Totals.copy(
              totalLive = 3,
              totalDead = 2,
              totalExisting = 2,
              mortalityRate = 50,
              cumulativeDead = 2)
      siteOther1Totals = siteOther1Totals.copy(totalLive = 2, mortalityRate = 33, permanentLive = 2)

      helper.assertTotals(
          setOf(
              siteOther1Totals,
              siteOther2Totals,
              siteSpecies1Totals,
              siteSpecies2Totals,
              siteSpecies3Totals,
              siteUnknownTotals,
              zone1Other1Totals,
              zone1Other2Totals,
              zone1Plot1Other1Totals,
              zone1Plot1Other2Totals,
              zone1Plot1UnknownTotals,
              zone1Plot1Species1Totals,
              zone1Plot1Species2Totals,
              zone1Plot1Species3Totals,
              zone1Plot2Species1Totals,
              zone1Plot2UnknownTotals,
              zone1Plot2Species3Totals,
              zone1Species1Totals,
              zone1Species2Totals,
              zone1UnknownTotals,
              zone1Species3Totals,
              zone2Other1Totals,
              zone2Plot1Other1Totals,
              zone2Plot1Species1Totals,
              zone2Species1Totals,
          ),
          "Totals after observation in second zone")

      assertEquals(
          listOf(PlantingSitePopulationsRow(plantingSiteId, inserted.speciesId, 3, 3)),
          plantingSitePopulationsDao.findAll(),
          "Planting site populations should not have changed")

      assertEquals(
          listOf(PlantingZonePopulationsRow(inserted.plantingZoneId, inserted.speciesId, 2, 2)),
          plantingZonePopulationsDao.findAll(),
          "Planting zone populations should not have changed")

      assertEquals(
          listOf(
              PlantingSubzonePopulationsRow(inserted.plantingSubzoneId, inserted.speciesId, 1, 1)),
          plantingSubzonePopulationsDao.findAll(),
          "Planting subzone populations should not have changed")
    }

    @Test
    fun `updates cumulative dead across observations`() {
      val speciesId = insertSpecies()
      insertObservationPlot(claimedBy = user.userId, isPermanent = true)

      val deadPlantsRow =
          RecordedPlantsRow(
              certaintyId = Known,
              gpsCoordinates = point(1),
              speciesId = speciesId,
              statusId = Dead)
      store.completePlot(
          observationId, plotId, emptySet(), null, Instant.EPOCH, listOf(deadPlantsRow))

      val observationId2 = insertObservation()
      insertObservationPlot(claimedBy = user.userId, isPermanent = true)

      store.completePlot(
          observationId2, plotId, emptySet(), null, Instant.EPOCH, listOf(deadPlantsRow))

      assertEquals(
          2,
          with(OBSERVED_PLOT_SPECIES_TOTALS) {
            dslContext
                .select(CUMULATIVE_DEAD)
                .from(this)
                .where(OBSERVATION_ID.eq(observationId2))
                .fetchOne(CUMULATIVE_DEAD)
          },
          "Plot cumulative dead for second observation")
      assertEquals(
          2,
          with(OBSERVED_ZONE_SPECIES_TOTALS) {
            dslContext
                .select(CUMULATIVE_DEAD)
                .from(this)
                .where(OBSERVATION_ID.eq(observationId2))
                .fetchOne(CUMULATIVE_DEAD)
          },
          "Zone cumulative dead for second observation")
      assertEquals(
          2,
          with(OBSERVED_SITE_SPECIES_TOTALS) {
            dslContext
                .select(CUMULATIVE_DEAD)
                .from(this)
                .where(OBSERVATION_ID.eq(observationId2))
                .fetchOne(CUMULATIVE_DEAD)
          },
          "Site cumulative dead for second observation")
    }

    @Test
    fun `marks observation as completed if this was the last incomplete plot`() {
      insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)

      val speciesId = insertSpecies()
      insertPlantingSitePopulation(totalPlants = 3, plantsSinceLastObservation = 3)
      insertPlantingZonePopulation(totalPlants = 2, plantsSinceLastObservation = 2)
      insertPlantingSubzonePopulation(totalPlants = 1, plantsSinceLastObservation = 1)

      clock.instant = Instant.ofEpochSecond(123)
      store.completePlot(observationId, plotId, emptySet(), null, Instant.EPOCH, emptyList())

      val observation = store.fetchObservationById(observationId)

      assertEquals(ObservationState.Completed, observation.state, "Observation state")
      assertEquals(clock.instant, observation.completedTime, "Completed time")

      assertEquals(
          listOf(PlantingSitePopulationsRow(plantingSiteId, speciesId, 3, 0)),
          plantingSitePopulationsDao.findAll(),
          "Planting site plants since last observation should have been reset")

      assertEquals(
          listOf(PlantingZonePopulationsRow(inserted.plantingZoneId, speciesId, 2, 0)),
          plantingZonePopulationsDao.findAll(),
          "Planting zone plants since last observation should have been reset")

      assertEquals(
          listOf(PlantingSubzonePopulationsRow(inserted.plantingSubzoneId, speciesId, 1, 0)),
          plantingSubzonePopulationsDao.findAll(),
          "Planting subzone plants since last observation should have been reset")
    }

    @Test
    fun `throws exception if plot was already completed`() {
      insertObservationPlot(
          ObservationPlotsRow(
              claimedBy = user.userId,
              claimedTime = Instant.EPOCH,
              completedBy = user.userId,
              completedTime = Instant.EPOCH,
              observedTime = Instant.EPOCH,
              statusId = ObservationPlotStatus.Completed,
          ))

      assertThrows<PlotAlreadyCompletedException> {
        store.completePlot(observationId, plotId, emptySet(), null, Instant.EPOCH, emptyList())
      }
    }

    @Test
    fun `throws exception if no permission to update observation`() {
      insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)

      every { user.canUpdateObservation(observationId) } returns false

      assertThrows<AccessDeniedException> {
        store.completePlot(observationId, plotId, emptySet(), null, Instant.EPOCH, emptyList())
      }
    }

    @Test
    fun `throws exception if monitoring plot not assigned to observation`() {
      assertThrows<PlotNotInObservationException> {
        store.completePlot(observationId, plotId, emptySet(), null, Instant.EPOCH, emptyList())
      }
    }
  }

  @Nested
  inner class PopulateCumulativeDead {
    private lateinit var plotId: MonitoringPlotId

    @BeforeEach
    fun setUp() {
      insertPlantingZone()
      insertPlantingSubzone()
      plotId = insertMonitoringPlot()
    }

    @Test
    fun `does not insert anything if this is the first observation of a site`() {
      insertPlantingSite(x = 0)
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      val otherSiteObservationId = insertObservation()
      insertObservationPlot(
          claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)
      store.completePlot(
          otherSiteObservationId,
          inserted.monitoringPlotId,
          emptySet(),
          null,
          Instant.EPOCH,
          listOf(
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = point(1),
                  speciesName = "Species name",
                  statusId = Dead)))

      val totalsForOtherSite = helper.fetchAllTotals()

      insertPlantingSite(x = 0)
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      val observationId = insertObservation()
      insertObservationPlot(isPermanent = true)

      store.populateCumulativeDead(observationId)

      assertEquals(totalsForOtherSite, helper.fetchAllTotals())
    }

    @Test
    fun `populates totals from permanent monitoring plots`() {
      val deadPlant =
          RecordedPlantsRow(
              certaintyId = Other,
              gpsCoordinates = point(1),
              speciesName = "Species name",
              statusId = Dead)

      insertPlantingSite(x = 0)
      insertPlantingZone()
      insertPlantingSubzone()
      val plotId1 = insertMonitoringPlot()
      val plotId2 = insertMonitoringPlot()

      val previousObservationId = insertObservation()

      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          isPermanent = true,
          monitoringPlotId = plotId1)
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          isPermanent = true,
          monitoringPlotId = plotId2)
      store.completePlot(
          previousObservationId, plotId1, emptySet(), null, Instant.EPOCH, listOf(deadPlant))
      store.completePlot(
          previousObservationId, plotId2, emptySet(), null, Instant.EPOCH, listOf(deadPlant))

      val totalsFromPreviousObservation = helper.fetchAllTotals()

      // In the next observation, plot 2 is no longer permanent.
      val observationId = insertObservation()
      insertObservationPlot(isPermanent = true, monitoringPlotId = plotId1)
      insertObservationPlot(isPermanent = false, monitoringPlotId = plotId2)

      store.populateCumulativeDead(observationId)

      val totalsForThisObservation = helper.fetchAllTotals() - totalsFromPreviousObservation

      assertEquals(
          setOf(
              ObservedPlotSpeciesTotalsRow(
                  observationId = observationId,
                  monitoringPlotId = plotId1,
                  speciesName = "Species name",
                  certaintyId = Other,
                  totalLive = 0,
                  totalDead = 0,
                  totalExisting = 0,
                  mortalityRate = 100,
                  cumulativeDead = 1,
                  permanentLive = 0,
              ),
              ObservedSiteSpeciesTotalsRow(
                  observationId = observationId,
                  plantingSiteId = inserted.plantingSiteId,
                  speciesName = "Species name",
                  certaintyId = Other,
                  totalLive = 0,
                  totalDead = 0,
                  totalExisting = 0,
                  mortalityRate = 100,
                  cumulativeDead = 1,
                  permanentLive = 0,
              ),
              ObservedZoneSpeciesTotalsRow(
                  observationId = observationId,
                  plantingZoneId = inserted.plantingZoneId,
                  speciesName = "Species name",
                  certaintyId = Other,
                  totalLive = 0,
                  totalDead = 0,
                  totalExisting = 0,
                  mortalityRate = 100,
                  cumulativeDead = 1,
                  permanentLive = 0,
              ),
          ),
          totalsForThisObservation)
    }

    @Test
    fun `only populates totals if there were dead plants`() {
      val livePlant =
          RecordedPlantsRow(
              certaintyId = Other,
              gpsCoordinates = point(1),
              speciesName = "Species name",
              statusId = Live)

      insertPlantingSite(x = 0)
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()

      val previousObservationId = insertObservation()

      insertObservationPlot(
          claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)
      store.completePlot(
          previousObservationId,
          inserted.monitoringPlotId,
          emptySet(),
          null,
          Instant.EPOCH,
          listOf(livePlant))

      val totalsFromPreviousObservation = helper.fetchAllTotals()

      val observationId = insertObservation()
      insertObservationPlot(isPermanent = true)

      store.populateCumulativeDead(observationId)

      assertEquals(totalsFromPreviousObservation, helper.fetchAllTotals())
    }

    @Test
    fun `throws exception if no permission to update observation`() {
      val observationId = insertObservation()

      every { user.canUpdateObservation(observationId) } returns false

      assertThrows<AccessDeniedException> { store.populateCumulativeDead(observationId) }
    }
  }

  @Nested
  inner class CountPlots {
    lateinit var plantingSiteId: PlantingSiteId

    @BeforeEach
    fun setUp() {
      plantingSiteId = insertPlantingSite(x = 0)
      insertPlantingZone()
      insertPlantingSubzone()
    }

    @Nested
    inner class ByPlantingSite {
      @Test
      fun `returns correct counts`() {
        val plotId1 = insertMonitoringPlot()
        val plotId2 = insertMonitoringPlot()
        val plotId3 = insertMonitoringPlot()
        val plotId4 = insertMonitoringPlot()
        val plotId5 = insertMonitoringPlot()
        val plotId6 = insertMonitoringPlot()

        val observationId1 = insertObservation()
        insertObservationPlot(monitoringPlotId = plotId1, claimedBy = user.userId)
        insertObservationPlot(monitoringPlotId = plotId2, claimedBy = user.userId)
        insertObservationPlot(monitoringPlotId = plotId3, completedBy = user.userId)
        insertObservationPlot(monitoringPlotId = plotId4)
        insertObservationPlot(monitoringPlotId = plotId5)

        val observationId2 = insertObservation()
        insertObservationPlot(monitoringPlotId = plotId1)
        insertObservationPlot(monitoringPlotId = plotId2)
        insertObservationPlot(monitoringPlotId = plotId6)

        // Make sure we're actually filtering by planting site
        insertPlantingSite()
        insertPlantingZone()
        insertPlantingSubzone()
        insertMonitoringPlot()
        insertObservation()
        insertObservationPlot()

        val expected =
            mapOf(
                observationId1 to
                    ObservationPlotCounts(
                        totalIncomplete = 4,
                        totalPlots = 5,
                        totalUnclaimed = 2,
                    ),
                observationId2 to
                    ObservationPlotCounts(
                        totalIncomplete = 3,
                        totalPlots = 3,
                        totalUnclaimed = 3,
                    ),
            )

        val actual = store.countPlots(plantingSiteId)

        assertEquals(expected, actual)
      }

      @Test
      fun `returns empty map if planting site has no observations`() {
        insertPlantingSite()

        assertEquals(
            emptyMap<ObservationId, ObservationPlotCounts>(),
            store.countPlots(inserted.plantingSiteId))
      }

      @Test
      fun `throws exception if no permission to read planting site`() {
        every { user.canReadPlantingSite(any()) } returns false

        assertThrows<PlantingSiteNotFoundException> { store.countPlots(insertPlantingSite()) }
      }
    }

    @Nested
    inner class ByObservation {
      @Test
      fun `returns correct counts`() {
        val plotId1 = insertMonitoringPlot()
        val plotId2 = insertMonitoringPlot()
        val plotId3 = insertMonitoringPlot()
        val plotId4 = insertMonitoringPlot()
        val plotId5 = insertMonitoringPlot()

        val observationId = insertObservation()
        insertObservationPlot(monitoringPlotId = plotId1, claimedBy = user.userId)
        insertObservationPlot(monitoringPlotId = plotId2, claimedBy = user.userId)
        insertObservationPlot(monitoringPlotId = plotId3, completedBy = user.userId)
        insertObservationPlot(monitoringPlotId = plotId4)
        insertObservationPlot(monitoringPlotId = plotId5)

        insertObservation()
        insertObservationPlot(monitoringPlotId = plotId1)

        val expected =
            ObservationPlotCounts(
                totalIncomplete = 4,
                totalPlots = 5,
                totalUnclaimed = 2,
            )

        val actual = store.countPlots(observationId)

        assertEquals(expected, actual)
      }

      @Test
      fun `returns plot counts of zero if no plots have been assigned to observation`() {
        val observationId = insertObservation()

        val expected = ObservationPlotCounts(0, 0, 0)

        val actual = store.countPlots(observationId)

        assertEquals(expected, actual)
      }

      @Test
      fun `throws exception if observation does not exist`() {
        assertThrows<ObservationNotFoundException> { store.countPlots(ObservationId(-1)) }
      }

      @Test
      fun `throws exception if no permission to read observation`() {
        every { user.canReadObservation(any()) } returns false

        assertThrows<ObservationNotFoundException> { store.countPlots(insertObservation()) }
      }
    }

    @Nested
    inner class ByOrganization {
      @BeforeEach
      fun setUp() {
        every { user.canReadOrganization(any()) } returns true
      }

      @Test
      fun `returns correct counts`() {
        // Plot not included in an observation
        insertMonitoringPlot()

        val observationId1 = insertObservation()
        insertObservationPlot(monitoringPlotId = insertMonitoringPlot(), claimedBy = user.userId)
        insertObservationPlot(monitoringPlotId = insertMonitoringPlot(), claimedBy = user.userId)
        insertObservationPlot(monitoringPlotId = insertMonitoringPlot(), completedBy = user.userId)
        insertObservationPlot(monitoringPlotId = insertMonitoringPlot())
        insertObservationPlot(monitoringPlotId = insertMonitoringPlot())

        insertPlantingSite()
        insertPlantingZone()
        insertPlantingSubzone()

        val observationId2 = insertObservation()
        insertObservationPlot(monitoringPlotId = insertMonitoringPlot(), claimedBy = user.userId)
        insertObservationPlot(monitoringPlotId = insertMonitoringPlot())

        // Make sure we're actually filtering by planting site
        insertOrganization()
        insertPlantingSite()
        insertPlantingZone()
        insertPlantingSubzone()
        insertMonitoringPlot()
        insertObservation()
        insertObservationPlot()

        val expected =
            mapOf(
                observationId1 to
                    ObservationPlotCounts(
                        totalIncomplete = 4,
                        totalPlots = 5,
                        totalUnclaimed = 2,
                    ),
                observationId2 to
                    ObservationPlotCounts(
                        totalIncomplete = 2,
                        totalPlots = 2,
                        totalUnclaimed = 1,
                    ),
            )

        val actual = store.countPlots(organizationId)

        assertEquals(expected, actual)
      }

      @Test
      fun `returns empty map if organization has no observations`() {
        insertPlantingSite()

        assertEquals(
            emptyMap<ObservationId, ObservationPlotCounts>(), store.countPlots(organizationId))
      }

      @Test
      fun `throws exception if no permission to read organization`() {
        every { user.canReadOrganization(organizationId) } returns false

        assertThrows<OrganizationNotFoundException> { store.countPlots(organizationId) }
      }
    }
  }

  @Nested
  inner class UpdatePlotObservation {
    @Test
    fun `can add and remove observed coordinates`() {
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      insertObservation()
      insertObservationPlot(completedBy = user.userId)
      insertObservedCoordinates(
          gpsCoordinates = point(1), position = ObservationPlotPosition.NorthwestCorner)

      store.updatePlotObservation(
          inserted.observationId,
          inserted.monitoringPlotId,
          listOf(
              NewObservedPlotCoordinatesModel(
                  gpsCoordinates = point(2), position = ObservationPlotPosition.SoutheastCorner),
              NewObservedPlotCoordinatesModel(
                  gpsCoordinates = point(1, 2), position = ObservationPlotPosition.SouthwestCorner),
          ))

      assertEquals(
          mapOf(
              ObservationPlotPosition.SouthwestCorner to point(1, 2),
              ObservationPlotPosition.SoutheastCorner to point(2, 2)),
          observedPlotCoordinatesDao.findAll().associate { it.positionId!! to it.gpsCoordinates!! },
          "Coordinates after update")
    }
  }

  @Nested
  inner class RemovePlotFromTotals {
    @Test
    fun `updates observed species totals across all observations`() {
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val speciesId3 = insertSpecies()

      val zoneId1 = insertPlantingZone()
      insertPlantingSubzone()
      val zone1PlotId1 = insertMonitoringPlot()
      val zone1PlotId2 = insertMonitoringPlot()
      val zoneId2 = insertPlantingZone()
      insertPlantingSubzone()
      val zone2PlotId1 = insertMonitoringPlot()

      val observationId1 = insertObservation(completedTime = Instant.ofEpochSecond(1))

      helper.insertObservationScenario(
          ObservationZone(
              zoneId = zoneId1,
              plots =
                  listOf(
                      ObservationPlot(
                          zone1PlotId1,
                          listOf(
                              PlantTotals(speciesId1, live = 2, dead = 1, existing = 1),
                              PlantTotals(speciesId2, live = 1),
                              PlantTotals("fern", live = 1, dead = 1),
                          ),
                      ),
                      ObservationPlot(
                          zone1PlotId2,
                          listOf(
                              PlantTotals(speciesId2, live = 1, dead = 1),
                          )))),
          ObservationZone(
              zoneId = zoneId2,
              plots =
                  listOf(
                      ObservationPlot(
                          zone2PlotId1,
                          listOf(
                              PlantTotals(speciesId1, live = 1),
                              PlantTotals(speciesId3, live = 1),
                          )))))

      val observationId2 = insertObservation(completedTime = Instant.ofEpochSecond(2))

      helper.insertObservationScenario(
          ObservationZone(
              zoneId = zoneId1,
              plots =
                  listOf(
                      ObservationPlot(
                          zone1PlotId1,
                          listOf(
                              PlantTotals(speciesId1, live = 1, dead = 1, existing = 1),
                              PlantTotals(speciesId2, live = 1),
                              PlantTotals("fern", live = 2),
                          ),
                      ),
                      ObservationPlot(
                          zone1PlotId2,
                          listOf(
                              PlantTotals(speciesId2, live = 1),
                          ))),
          ))

      store.removePlotFromTotals(zone1PlotId1)

      helper.assertTotals(
          setOf(
              ObservedPlotSpeciesTotalsRow(
                  observationId = observationId1,
                  monitoringPlotId = zone1PlotId1,
                  speciesId = speciesId1,
                  speciesName = null,
                  certaintyId = Known,
                  totalLive = 2,
                  totalDead = 1,
                  totalExisting = 1,
                  mortalityRate = 33,
                  cumulativeDead = 1,
                  permanentLive = 2),
              // Parameter names omitted after this to keep the test method size manageable.
              ObservedPlotSpeciesTotalsRow(
                  observationId1, zone1PlotId1, speciesId2, null, Known, 1, 0, 0, 0, 0, 1),
              ObservedPlotSpeciesTotalsRow(
                  observationId1, zone1PlotId1, null, "fern", Other, 1, 1, 0, 50, 1, 1),
              ObservedPlotSpeciesTotalsRow(
                  observationId1, zone1PlotId2, speciesId2, null, Known, 1, 1, 0, 50, 1, 1),
              ObservedPlotSpeciesTotalsRow(
                  observationId1, zone2PlotId1, speciesId1, null, Known, 1, 0, 0, 0, 0, 1),
              ObservedPlotSpeciesTotalsRow(
                  observationId1, zone2PlotId1, speciesId3, null, Known, 1, 0, 0, 0, 0, 1),
              ObservedZoneSpeciesTotalsRow(
                  observationId1, zoneId1, speciesId1, null, Known, 0, 0, 0, 0, 0, 0),
              ObservedZoneSpeciesTotalsRow(
                  observationId1, zoneId1, speciesId2, null, Known, 1, 1, 0, 50, 1, 1),
              ObservedZoneSpeciesTotalsRow(
                  observationId1, zoneId1, null, "fern", Other, 0, 0, 0, 0, 0, 0),
              ObservedZoneSpeciesTotalsRow(
                  observationId1, zoneId2, speciesId1, null, Known, 1, 0, 0, 0, 0, 1),
              ObservedZoneSpeciesTotalsRow(
                  observationId1, zoneId2, speciesId3, null, Known, 1, 0, 0, 0, 0, 1),
              ObservedSiteSpeciesTotalsRow(
                  observationId1, plantingSiteId, speciesId1, null, Known, 1, 0, 0, 0, 0, 1),
              ObservedSiteSpeciesTotalsRow(
                  observationId1, plantingSiteId, speciesId2, null, Known, 1, 1, 0, 50, 1, 1),
              ObservedSiteSpeciesTotalsRow(
                  observationId1, plantingSiteId, speciesId3, null, Known, 1, 0, 0, 0, 0, 1),
              ObservedSiteSpeciesTotalsRow(
                  observationId1, plantingSiteId, null, "fern", Other, 0, 0, 0, 0, 0, 0),
              ObservedPlotSpeciesTotalsRow(
                  observationId2, zone1PlotId1, speciesId1, null, Known, 1, 1, 1, 67, 2, 1),
              ObservedPlotSpeciesTotalsRow(
                  observationId2, zone1PlotId1, speciesId2, null, Known, 1, 0, 0, 0, 0, 1),
              ObservedPlotSpeciesTotalsRow(
                  observationId2, zone1PlotId1, null, "fern", Other, 2, 0, 0, 33, 1, 2),
              ObservedPlotSpeciesTotalsRow(
                  observationId2, zone1PlotId2, speciesId2, null, Known, 1, 0, 0, 50, 1, 1),
              ObservedZoneSpeciesTotalsRow(
                  observationId2, zoneId1, speciesId1, null, Known, 0, 0, 0, 0, 0, 0),
              ObservedZoneSpeciesTotalsRow(
                  observationId2, zoneId1, speciesId2, null, Known, 1, 0, 0, 50, 1, 1),
              ObservedZoneSpeciesTotalsRow(
                  observationId2, zoneId1, null, "fern", Other, 0, 0, 0, 0, 0, 0),
              ObservedSiteSpeciesTotalsRow(
                  observationId2, plantingSiteId, speciesId1, null, Known, 0, 0, 0, 0, 0, 0),
              ObservedSiteSpeciesTotalsRow(
                  observationId2, plantingSiteId, speciesId2, null, Known, 1, 0, 0, 50, 1, 1),
              ObservedSiteSpeciesTotalsRow(
                  observationId2, plantingSiteId, null, "fern", Other, 0, 0, 0, 0, 0, 0),
          ),
          "Totals after plot removal")
    }

    @Test
    fun `does not modify totals if plot has no recorded plants`() {
      helper.insertPlantedSite()
      val plotWithPlants = insertMonitoringPlot()
      insertObservation(completedTime = Instant.EPOCH)
      helper.insertObservationScenario(
          ObservationZone(
              zoneId = inserted.plantingZoneId,
              plots =
                  listOf(
                      ObservationPlot(
                          plotWithPlants,
                          listOf(
                              PlantTotals(inserted.speciesId, live = 3, dead = 2, existing = 1))))))

      val totalsBeforeRemoval = helper.fetchAllTotals()

      val plotWithoutPlants = insertMonitoringPlot()
      insertObservation()
      insertObservationPlot(completedTime = Instant.EPOCH)

      store.removePlotFromTotals(plotWithoutPlants)

      helper.assertTotals(totalsBeforeRemoval, "Totals after plot removal")
    }
  }
}
