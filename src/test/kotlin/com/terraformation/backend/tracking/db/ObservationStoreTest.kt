package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.CantTell
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Known
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Other
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotConditionsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSiteSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedZoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.NewObservationModel
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
        recordedPlantsDao)
  }

  private lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    plantingSiteId = insertPlantingSite()

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
      insertPlantingSubzone()
      insertMonitoringPlot()
      insertObservationPlot()

      // Observation in a different planting site
      insertPlantingSite()
      insertObservation()

      val expected =
          listOf(
              ExistingObservationModel(
                  endDate = endDate1,
                  id = observationId2,
                  plantingSiteId = plantingSiteId,
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
      val userId1 = UserId(101)
      val userId2 = UserId(102)
      insertUser(userId1, firstName = "First", lastName = "Person")
      insertUser(userId2, firstName = "Second", lastName = "Human")

      insertPlantingZone(name = "Z1")
      val plantingSubzoneId1 = insertPlantingSubzone(fullName = "Z1-S1", name = "S1")

      // A plot that was observed previously and again in this observation
      val monitoringPlotId11 =
          insertMonitoringPlot(boundary = polygon(1.0), fullName = "Z1-S1-1", name = "1")
      insertObservation()
      insertObservationPlot()
      val observationId = insertObservation()
      insertObservationPlot(isPermanent = true)

      // This plot is claimed
      val monitoringPlotId12 =
          insertMonitoringPlot(boundary = polygon(2.0), fullName = "Z1-S1-2", name = "2")
      val claimedTime12 = Instant.ofEpochSecond(12)
      insertObservationPlot(ObservationPlotsRow(claimedBy = userId1, claimedTime = claimedTime12))

      val plantingSubzoneId2 = insertPlantingSubzone(fullName = "Z1-S2", name = "S2")

      // This plot is claimed and completed
      val monitoringPlotId21 =
          insertMonitoringPlot(boundary = polygon(3.0), fullName = "Z1-S2-1", name = "1")
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
              observedTime = observedTime21))

      val expected =
          listOf(
              AssignedPlotDetails(
                  model =
                      ObservationPlotModel(
                          isPermanent = true,
                          monitoringPlotId = monitoringPlotId11,
                          observationId = observationId,
                      ),
                  boundary = polygon(1.0),
                  claimedByName = null,
                  completedByName = null,
                  isFirstObservation = false,
                  plantingSubzoneId = plantingSubzoneId1,
                  plantingSubzoneName = "Z1-S1",
                  plotName = "Z1-S1-1",
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
                  boundary = polygon(2.0),
                  claimedByName = "First Person",
                  completedByName = null,
                  isFirstObservation = true,
                  plantingSubzoneId = plantingSubzoneId1,
                  plantingSubzoneName = "Z1-S1",
                  plotName = "Z1-S1-2",
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
                  boundary = polygon(3.0),
                  claimedByName = "Second Human",
                  completedByName = "First Person",
                  isFirstObservation = true,
                  plantingSubzoneId = plantingSubzoneId2,
                  plantingSubzoneName = "Z1-S2",
                  plotName = "Z1-S2-1",
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
      val timeZone = insertTimeZone("America/Denver")
      val startDate = LocalDate.of(2023, 4, 1)
      val endDate = LocalDate.of(2023, 4, 30)

      val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
      clock.instant = now

      insertSiteAndPlanting(timeZone)
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
      val zone1 = insertTimeZone("America/Los_Angeles")
      val zone2 = insertTimeZone("America/Denver")
      val zone3 = insertTimeZone("America/Chicago")

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
      insertSiteAndPlanting(null)
      val observationId1 =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      // Start date is an hour ago.
      insertSiteAndPlanting(timeZone = zone3)
      val observationId2 =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      // Start date hasn't arrived yet in the site's time zone.
      insertSiteAndPlanting(timeZone = zone1)
      insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      // Observation already in progress; shouldn't be started
      insertSiteAndPlanting(timeZone = zone3)
      insertObservation(
          endDate = endDate, startDate = startDate, state = ObservationState.InProgress)

      // Start date is still in the future.
      insertSiteAndPlanting(timeZone = zone3)
      insertObservation(
          endDate = endDate, startDate = startDate.plusDays(1), state = ObservationState.Upcoming)

      val expected = setOf(observationId1, observationId2)
      val actual = store.fetchStartableObservations().map { it.id }.toSet()

      assertEquals(expected, actual)
    }

    @Test
    fun `limits results to requested planting site`() {
      val timeZone = insertTimeZone("America/Denver")

      val startDate = LocalDate.of(2023, 4, 1)
      val endDate = LocalDate.of(2023, 4, 30)

      val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
      clock.instant = now

      val plantingSiteId = insertSiteAndPlanting(timeZone = timeZone)
      val observationId =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      insertPlantingSite(timeZone = timeZone)
      insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)

      val expected = setOf(observationId)
      val actual = store.fetchStartableObservations(plantingSiteId).map { it.id }.toSet()

      assertEquals(expected, actual)
    }

    private fun insertSiteAndPlanting(timeZone: ZoneId?): PlantingSiteId {
      val plantingSiteId = insertPlantingSite(timeZone = timeZone)
      insertPlantingZone()
      insertPlantingSubzone()
      insertWithdrawal()
      insertDelivery()
      insertPlanting()

      return plantingSiteId
    }
  }

  @Nested
  inner class FetchObservationsPastEndDate {
    @Test
    fun `honors planting site time zones`() {
      // Three adjacent time zones, 1 hour apart
      val zone1 = insertTimeZone("America/Los_Angeles")
      val zone2 = insertTimeZone("America/Denver")
      val zone3 = insertTimeZone("America/Chicago")

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
      val timeZone = insertTimeZone("America/Denver")

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
      val observationId =
          store.createObservation(
              NewObservationModel(
                  completedTime = Instant.EPOCH,
                  endDate = LocalDate.of(2020, 1, 31),
                  id = null,
                  plantingSiteId = plantingSiteId,
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

      assertEquals(user.userId, row.claimedBy, "Claimed by")
      assertEquals(clock.instant, row.claimedTime, "Claimed time")
    }

    @Test
    fun `updates claim time if plot is reclaimed by current claimant`() {
      insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)

      clock.instant = Instant.ofEpochSecond(2)

      store.claimPlot(observationId, plotId)

      val plotsRow = observationPlotsDao.findAll().first()

      assertEquals(user.userId, plotsRow.claimedBy, "Should remain claimed by user")
      assertEquals(clock.instant, plotsRow.claimedTime, "Claim time should be updated")
    }

    @Test
    fun `throws exception if plot is claimed by someone else`() {
      val otherUserId = UserId(100)
      insertUser(otherUserId)

      insertObservationPlot(claimedBy = otherUserId, claimedTime = Instant.EPOCH)

      assertThrows<PlotAlreadyClaimedException> { store.claimPlot(observationId, plotId) }
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
      insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)

      store.releasePlot(observationId, plotId)

      val row = observationPlotsDao.findAll().first()

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
      val otherUserId = UserId(100)
      insertUser(otherUserId)

      insertObservationPlot(claimedBy = otherUserId, claimedTime = Instant.EPOCH)

      assertThrows<PlotAlreadyClaimedException> { store.releasePlot(observationId, plotId) }
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
                  gpsCoordinates = point(1.0),
                  speciesId = speciesId,
                  statusId = RecordedPlantStatus.Live,
              ),
              RecordedPlantsRow(
                  certaintyId = CantTell,
                  gpsCoordinates = point(2.0),
                  statusId = RecordedPlantStatus.Dead,
              ),
              RecordedPlantsRow(
                  certaintyId = RecordedSpeciesCertainty.Other,
                  gpsCoordinates = point(3.0),
                  speciesName = "Who knows",
                  statusId = RecordedPlantStatus.Existing,
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
                      observedTime = observedTime)
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
      insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)
      val zoneId1 = inserted.plantingZoneId
      val zone1PlotId2 = insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)
      val zoneId2 = insertPlantingZone()
      insertPlantingSubzone()
      val zone2PlotId1 = insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)

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
                  gpsCoordinates = point(1.0),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Live,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1.0),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Live,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1.0),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Dead,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1.0),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Existing,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1.0),
                  speciesId = speciesId2,
                  statusId = RecordedPlantStatus.Dead,
              ),
              RecordedPlantsRow(
                  certaintyId = RecordedSpeciesCertainty.Other,
                  gpsCoordinates = point(1.0),
                  speciesName = "Other 1",
                  statusId = RecordedPlantStatus.Live,
              ),
              RecordedPlantsRow(
                  certaintyId = RecordedSpeciesCertainty.Other,
                  gpsCoordinates = point(1.0),
                  speciesName = "Other 1",
                  statusId = RecordedPlantStatus.Dead,
              ),
              RecordedPlantsRow(
                  certaintyId = RecordedSpeciesCertainty.Other,
                  gpsCoordinates = point(1.0),
                  speciesName = "Other 2",
                  statusId = RecordedPlantStatus.Live,
              ),
              RecordedPlantsRow(
                  certaintyId = CantTell,
                  gpsCoordinates = point(1.0),
                  statusId = RecordedPlantStatus.Live,
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
              totalPlants = 3,
              mortalityRate = 33)
      // Parameter names omitted after this to keep the test method size manageable.
      val zone1Plot1Species2Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, plotId, speciesId2, null, Known, 0, 1, 0, 1, 100)
      val zone1Plot1Other1Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, plotId, null, "Other 1", Other, 1, 1, 0, 2, 50)
      val zone1Plot1Other2Totals =
          ObservedPlotSpeciesTotalsRow(observationId, plotId, null, "Other 2", Other, 1, 0, 0, 1, 0)
      val zone1Plot1CantTellTotals =
          ObservedPlotSpeciesTotalsRow(observationId, plotId, null, null, CantTell, 1, 0, 0, 1, 0)
      var siteSpecies1Totals =
          ObservedSiteSpeciesTotalsRow(
              observationId, inserted.plantingSiteId, speciesId1, null, Known, 2, 1, 1, 3, 33)
      val siteSpecies2Totals =
          ObservedSiteSpeciesTotalsRow(
              observationId, inserted.plantingSiteId, speciesId2, null, Known, 0, 1, 0, 1, 100)
      var siteOther1Totals =
          ObservedSiteSpeciesTotalsRow(
              observationId, plantingSiteId, null, "Other 1", Other, 1, 1, 0, 2, 50)
      val siteOther2Totals =
          ObservedSiteSpeciesTotalsRow(
              observationId, plantingSiteId, null, "Other 2", Other, 1, 0, 0, 1, 0)
      var siteCantTellTotals =
          ObservedSiteSpeciesTotalsRow(
              observationId, plantingSiteId, null, null, CantTell, 1, 0, 0, 1, 0)
      var zone1Species1Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId1, speciesId1, null, Known, 2, 1, 1, 3, 33)
      val zone1Species2Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId1, speciesId2, null, Known, 0, 1, 0, 1, 100)
      val zone1Other1Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId1, null, "Other 1", Other, 1, 1, 0, 2, 50)
      val zone1Other2Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId1, null, "Other 2", Other, 1, 0, 0, 1, 0)
      var zone1CantTellTotals =
          ObservedZoneSpeciesTotalsRow(observationId, zoneId1, null, null, CantTell, 1, 0, 0, 1, 0)

      assertTotals(
          setOf(
              siteCantTellTotals,
              siteOther1Totals,
              siteOther2Totals,
              siteSpecies1Totals,
              siteSpecies2Totals,
              zone1CantTellTotals,
              zone1Other1Totals,
              zone1Other2Totals,
              zone1Plot1CantTellTotals,
              zone1Plot1Other1Totals,
              zone1Plot1Other2Totals,
              zone1Plot1Species1Totals,
              zone1Plot1Species2Totals,
              zone1Species1Totals,
              zone1Species2Totals,
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
                  gpsCoordinates = point(1.0),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Live),
              RecordedPlantsRow(
                  certaintyId = CantTell,
                  gpsCoordinates = point(1.0),
                  statusId = RecordedPlantStatus.Live,
              ),
          ))

      val zone1Plot2Species1Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, zone1PlotId2, speciesId1, null, Known, 1, 0, 0, 1, 0)
      val zone1Plot2CantTellTotals =
          ObservedPlotSpeciesTotalsRow(
              observationId, zone1PlotId2, null, null, CantTell, 1, 0, 0, 1, 0)
      siteSpecies1Totals =
          siteSpecies1Totals.copy(totalLive = 3, totalPlants = 4, mortalityRate = 25)
      siteCantTellTotals = siteCantTellTotals.copy(totalLive = 2, totalPlants = 2)
      zone1Species1Totals =
          zone1Species1Totals.copy(totalLive = 3, totalPlants = 4, mortalityRate = 25)
      zone1CantTellTotals = zone1CantTellTotals.copy(totalLive = 2, totalPlants = 2)

      assertTotals(
          setOf(
              siteCantTellTotals,
              siteOther1Totals,
              siteOther2Totals,
              siteSpecies1Totals,
              siteSpecies2Totals,
              zone1CantTellTotals,
              zone1Other1Totals,
              zone1Other2Totals,
              zone1Plot1CantTellTotals,
              zone1Plot1Other1Totals,
              zone1Plot1Other2Totals,
              zone1Plot1Species1Totals,
              zone1Plot1Species2Totals,
              zone1Plot2CantTellTotals,
              zone1Plot2Species1Totals,
              zone1Species1Totals,
              zone1Species2Totals,
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
                  gpsCoordinates = point(1.0),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Dead,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1.0),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Existing,
              ),
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = point(1.0),
                  speciesName = "Other 1",
                  statusId = RecordedPlantStatus.Live)))

      val zone2Plot1Species1Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, zone2PlotId1, speciesId1, null, Known, 0, 1, 1, 1, 100)
      val zone2Plot1Other1Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, zone2PlotId1, null, "Other 1", Other, 1, 0, 0, 1, 0)
      val zone2Species1Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId2, speciesId1, null, Known, 0, 1, 1, 1, 100)
      val zone2Other1Totals =
          ObservedZoneSpeciesTotalsRow(
              observationId, zoneId2, null, "Other 1", Other, 1, 0, 0, 1, 0)
      siteSpecies1Totals =
          siteSpecies1Totals.copy(
              totalLive = 3, totalDead = 2, totalExisting = 2, totalPlants = 5, mortalityRate = 40)
      siteOther1Totals = siteOther1Totals.copy(totalLive = 2, totalPlants = 3, mortalityRate = 33)

      assertTotals(
          setOf(
              siteCantTellTotals,
              siteOther1Totals,
              siteOther2Totals,
              siteSpecies1Totals,
              siteSpecies2Totals,
              zone1CantTellTotals,
              zone1Other1Totals,
              zone1Other2Totals,
              zone1Plot1CantTellTotals,
              zone1Plot1Other1Totals,
              zone1Plot1Other2Totals,
              zone1Plot1Species1Totals,
              zone1Plot1Species2Totals,
              zone1Plot2CantTellTotals,
              zone1Plot2Species1Totals,
              zone1Species1Totals,
              zone1Species2Totals,
              zone2Other1Totals,
              zone2Plot1Other1Totals,
              zone2Plot1Species1Totals,
              zone2Species1Totals,
          ),
          "Totals after observation in second zone")
    }

    @Test
    fun `marks observation as completed if this was the last incomplete plot`() {
      insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)

      clock.instant = Instant.ofEpochSecond(123)
      store.completePlot(observationId, plotId, emptySet(), null, Instant.EPOCH, emptyList())

      val observation = store.fetchObservationById(observationId)

      assertEquals(ObservationState.Completed, observation.state, "Observation state")
      assertEquals(clock.instant, observation.completedTime, "Completed time")
    }

    @Test
    fun `throws exception if plot was already completed`() {
      insertObservationPlot(
          ObservationPlotsRow(
              claimedBy = user.userId,
              claimedTime = Instant.EPOCH,
              completedBy = user.userId,
              completedTime = Instant.EPOCH,
              observedTime = Instant.EPOCH))

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

  private fun fetchAllTotals(): Set<Any> {
    return (dslContext
            .selectFrom(OBSERVED_PLOT_SPECIES_TOTALS)
            .fetchInto(ObservedPlotSpeciesTotalsRow::class.java) +
            dslContext
                .selectFrom(OBSERVED_ZONE_SPECIES_TOTALS)
                .fetchInto(ObservedZoneSpeciesTotalsRow::class.java) +
            dslContext
                .selectFrom(OBSERVED_SITE_SPECIES_TOTALS)
                .fetchInto(ObservedSiteSpeciesTotalsRow::class.java))
        .toSet()
  }

  /**
   * Asserts that the contents of the observed totals tables match an expected set of rows. If
   * there's a difference, produces a textual assertion failure so the difference is easy to spot in
   * the test output.
   */
  private fun assertTotals(expected: Set<Any>, message: String) {
    val actual = fetchAllTotals()

    if (expected != actual) {
      val expectedRows = expected.map { "$it" }.sorted().joinToString("\n")
      val actualRows = actual.map { "$it" }.sorted().joinToString("\n")
      assertEquals(expectedRows, actualRows, message)
    }
  }
}
