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
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus.Dead
import com.terraformation.backend.db.tracking.RecordedPlantStatus.Existing
import com.terraformation.backend.db.tracking.RecordedPlantStatus.Live
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Known
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Other
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Unknown
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.embeddables.pojos.ObservationPlotId
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotConditionsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSiteSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSubzoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedZoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotConditionsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedPlotSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedSiteSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedZoneSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingSitePopulationsRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingSubzonePopulationsRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingZonePopulationsRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedPlantsRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedTreesRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_PLANTS
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.db.ObservationTestHelper.ObservationPlot
import com.terraformation.backend.tracking.db.ObservationTestHelper.ObservationZone
import com.terraformation.backend.tracking.db.ObservationTestHelper.PlantTotals
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.BiomassQuadratModel
import com.terraformation.backend.tracking.model.BiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.BiomassSpeciesKey
import com.terraformation.backend.tracking.model.BiomassSpeciesModel
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.NewBiomassDetailsModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NewObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.NewRecordedTreeModel
import com.terraformation.backend.tracking.model.ObservationPlotCounts
import com.terraformation.backend.tracking.model.ObservationPlotModel
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    every { user.canScheduleAdHocObservation(any()) } returns true
    every { user.canUpdateObservation(any()) } returns true
  }

  @Nested
  inner class FetchActiveObservationIds {
    @Test
    fun `returns observations with active plots in requested zones`() {
      val plantingZoneId1 = insertPlantingZone()
      insertPlantingSubzone()
      val zone1PlotId1 = insertMonitoringPlot()
      val zone1PlotId2 = insertMonitoringPlot()
      val plantingZoneId2 = insertPlantingZone()
      insertPlantingSubzone()
      val zone2PlotId1 = insertMonitoringPlot()
      val plantingZoneId3 = insertPlantingZone()
      insertPlantingSubzone()
      val zone3PlotId1 = insertMonitoringPlot()

      val observationId1 = insertObservation()
      insertObservationPlot(monitoringPlotId = zone1PlotId1)
      insertObservationPlot(monitoringPlotId = zone1PlotId2)
      insertObservationPlot(monitoringPlotId = zone2PlotId1)
      val observationId2 = insertObservation()
      insertObservationPlot(monitoringPlotId = zone2PlotId1)
      val observationId3 = insertObservation()
      insertObservationPlot(monitoringPlotId = zone3PlotId1)

      assertEquals(
          listOf(observationId1),
          store.fetchActiveObservationIds(plantingSiteId, listOf(plantingZoneId1)),
          "Observation with two plots in zone should be listed once")
      assertEquals(
          listOf(observationId1, observationId2),
          store.fetchActiveObservationIds(plantingSiteId, listOf(plantingZoneId2)),
          "Observations with plots in multiple zones should be returned")
      assertEquals(
          listOf(observationId1, observationId3),
          store.fetchActiveObservationIds(plantingSiteId, listOf(plantingZoneId1, plantingZoneId3)),
          "Should match observations in all requested zones")
    }

    @Test
    fun `does not return observation if its plots in the requested zones are completed`() {
      insertPlantingZone()
      insertPlantingSubzone()
      val monitoringPlotId1 = insertMonitoringPlot()
      val plantingZoneId2 = insertPlantingZone()
      insertPlantingSubzone()
      val monitoringPlotId2 = insertMonitoringPlot()

      val observationIdWithActivePlotsInBothZones = insertObservation()
      insertObservationPlot(monitoringPlotId = monitoringPlotId1)
      insertObservationPlot(monitoringPlotId = monitoringPlotId2)

      // Active plot in zone 1, completed plot in zone 2
      insertObservation()
      insertObservationPlot(monitoringPlotId = monitoringPlotId1)
      insertObservationPlot(monitoringPlotId = monitoringPlotId2, completedBy = user.userId)

      // Abandoned observation
      insertObservation(completedTime = Instant.EPOCH, state = ObservationState.Abandoned)
      insertObservationPlot(
          monitoringPlotId = monitoringPlotId2, statusId = ObservationPlotStatus.NotObserved)

      assertEquals(
          listOf(observationIdWithActivePlotsInBothZones),
          store.fetchActiveObservationIds(plantingSiteId, listOf(plantingZoneId2)))
    }

    @Test
    fun `does not return ad-hoc observation`() {
      val plantingZoneId = insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot(isAdHoc = true)
      insertObservation(isAdHoc = true)
      insertObservationPlot()

      assertEquals(
          emptyList<ObservationId>(),
          store.fetchActiveObservationIds(plantingSiteId, listOf(plantingZoneId)))
    }

    @Test
    fun `throws exception if no permission to read planting site`() {
      every { user.canReadPlantingSite(any()) } returns false

      assertThrows<PlantingSiteNotFoundException> {
        store.fetchActiveObservationIds(plantingSiteId, emptyList())
      }
    }
  }

  @Nested
  inner class FetchObservationsByPlantingSite {
    @Test
    fun `returns observations in date order`() {
      val plantingSiteHistoryId = inserted.plantingSiteHistoryId
      val startDate1 = LocalDate.of(2021, 4, 1)
      val startDate2 = LocalDate.of(2022, 3, 1)
      val startDate3 = LocalDate.of(2023, 3, 1)
      val endDate1 = LocalDate.of(2021, 4, 30)
      val endDate2 = LocalDate.of(2022, 3, 31)
      val endDate3 = LocalDate.of(2023, 3, 31)

      // Ad-hoc observations are excluded by default
      val adHocObservationId =
          insertObservation(
              endDate = endDate3,
              isAdHoc = true,
              startDate = startDate3,
              state = ObservationState.Upcoming)

      // Insert in reverse time order
      val observationId1 =
          insertObservation(
              endDate = endDate2, startDate = startDate2, state = ObservationState.Upcoming)

      val observationId2 =
          insertObservation(
              endDate = endDate1,
              plantingSiteHistoryId = plantingSiteHistoryId,
              startDate = startDate1)

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
                  isAdHoc = false,
                  observationType = ObservationType.Monitoring,
                  plantingSiteHistoryId = plantingSiteHistoryId,
                  plantingSiteId = plantingSiteId,
                  requestedSubzoneIds = setOf(subzoneId),
                  startDate = startDate1,
                  state = ObservationState.InProgress,
              ),
              ExistingObservationModel(
                  endDate = endDate2,
                  id = observationId1,
                  isAdHoc = false,
                  observationType = ObservationType.Monitoring,
                  plantingSiteId = plantingSiteId,
                  startDate = startDate2,
                  state = ObservationState.Upcoming,
              ),
          )

      val actual = store.fetchObservationsByPlantingSite(plantingSiteId)

      assertEquals(expected, actual, "Non-ad-hoc observations")

      assertEquals(
          listOf(
              ExistingObservationModel(
                  endDate = endDate3,
                  id = adHocObservationId,
                  isAdHoc = true,
                  observationType = ObservationType.Monitoring,
                  plantingSiteId = plantingSiteId,
                  startDate = startDate3,
                  state = ObservationState.Upcoming,
              )),
          store.fetchObservationsByPlantingSite(plantingSiteId, isAdHoc = true),
          "Ad-hoc observations")
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
          insertMonitoringPlot(boundary = polygon(1), elevationMeters = BigDecimal.TEN)
      insertObservation()
      insertObservationPlot()
      val observationId = insertObservation()
      insertObservationPlot(isPermanent = true)

      // This plot is claimed
      val monitoringPlotId12 = insertMonitoringPlot(boundary = polygon(2))
      val claimedTime12 = Instant.ofEpochSecond(12)
      insertObservationPlot(
          ObservationPlotsRow(
              claimedBy = userId1,
              claimedTime = claimedTime12,
              statusId = ObservationPlotStatus.Claimed))

      val plantingSubzoneId2 = insertPlantingSubzone(fullName = "Z1-S2", name = "S2")

      // This plot is claimed and completed
      val monitoringPlotId21 = insertMonitoringPlot(boundary = polygon(3))
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
                  elevationMeters = BigDecimal.TEN,
                  isFirstObservation = false,
                  plantingSubzoneId = plantingSubzoneId1,
                  plantingSubzoneName = "Z1-S1",
                  plotNumber = 1,
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
                  elevationMeters = null,
                  isFirstObservation = true,
                  plantingSubzoneId = plantingSubzoneId1,
                  plantingSubzoneName = "Z1-S1",
                  plotNumber = 2,
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
                  elevationMeters = null,
                  isFirstObservation = true,
                  plantingSubzoneId = plantingSubzoneId2,
                  plantingSubzoneName = "Z1-S2",
                  plotNumber = 3,
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
  inner class FetchOneObservationPlotDetails {
    @Test
    fun `calculates correct values from related tables`() {
      val userId1 = insertUser(firstName = "First", lastName = "Person")
      val userId2 = insertUser(firstName = "Second", lastName = "Human")

      insertPlantingZone(name = "Z1")
      val plantingSubzoneId1 = insertPlantingSubzone(fullName = "Z1-S1", name = "S1")

      // A plot that was observed previously and again in this observation
      val monitoringPlotId11 =
          insertMonitoringPlot(boundary = polygon(1), elevationMeters = BigDecimal.TEN)
      insertObservation()
      insertObservationPlot()
      val observationId = insertObservation()
      insertObservationPlot(isPermanent = true)

      // This plot is claimed
      val monitoringPlotId12 = insertMonitoringPlot(boundary = polygon(2))
      val claimedTime12 = Instant.ofEpochSecond(12)
      insertObservationPlot(
          ObservationPlotsRow(
              claimedBy = userId1,
              claimedTime = claimedTime12,
              statusId = ObservationPlotStatus.Claimed))

      val plantingSubzoneId2 = insertPlantingSubzone(fullName = "Z1-S2", name = "S2")

      // This plot is claimed and completed
      val monitoringPlotId21 = insertMonitoringPlot(boundary = polygon(3))
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

      assertEquals(
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
              elevationMeters = BigDecimal.TEN,
              isFirstObservation = false,
              plantingSubzoneId = plantingSubzoneId1,
              plantingSubzoneName = "Z1-S1",
              plotNumber = 1,
              sizeMeters = 30,
          ),
          store.fetchOneObservationPlotDetails(observationId, monitoringPlotId11),
          "Plot 11")

      assertEquals(
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
              elevationMeters = null,
              isFirstObservation = true,
              plantingSubzoneId = plantingSubzoneId1,
              plantingSubzoneName = "Z1-S1",
              plotNumber = 2,
              sizeMeters = 30,
          ),
          store.fetchOneObservationPlotDetails(observationId, monitoringPlotId12),
          "Plot 12")

      assertEquals(
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
              elevationMeters = null,
              isFirstObservation = true,
              plantingSubzoneId = plantingSubzoneId2,
              plantingSubzoneName = "Z1-S2",
              plotNumber = 3,
              sizeMeters = 30,
          ),
          store.fetchOneObservationPlotDetails(observationId, monitoringPlotId21),
          "Plot 21")
    }

    @Test
    fun `throws exception if no permission to read observation`() {
      every { user.canReadObservation(any()) } returns false

      val observationId = insertObservation()
      val monitoringPlotId11 = insertMonitoringPlot(boundary = polygon(1))
      insertObservationPlot()

      assertThrows<ObservationNotFoundException> {
        store.fetchOneObservationPlotDetails(observationId, monitoringPlotId11)
      }
    }

    @Test
    fun `throws exception if plot is not assigned to observation`() {
      val observationId = insertObservation()
      val monitoringPlotId11 = insertMonitoringPlot(boundary = polygon(1))

      assertThrows<ObservationPlotNotFoundException> {
        store.fetchOneObservationPlotDetails(observationId, monitoringPlotId11)
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
    fun `only returns observations with requested subzones`() {
      val timeZone = ZoneId.of("America/Denver")
      val startDate = LocalDate.of(2023, 4, 1)
      val endDate = LocalDate.of(2023, 4, 30)

      val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
      clock.instant = now

      helper.insertPlantedSite(timeZone = timeZone)
      val startableObservationId =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)
      insertObservationRequestedSubzone()

      // Another planting site with no requested subzone.
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
      insertObservationRequestedSubzone()

      // Start date is an hour ago.
      helper.insertPlantedSite(timeZone = zone3)
      val observationId2 =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)
      insertObservationRequestedSubzone()

      // Start date hasn't arrived yet in the site's time zone.
      helper.insertPlantedSite(timeZone = zone1)
      insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)
      insertObservationRequestedSubzone()

      // Observation already in progress; shouldn't be started
      helper.insertPlantedSite(timeZone = zone3)
      insertObservation(
          endDate = endDate, startDate = startDate, state = ObservationState.InProgress)
      insertObservationRequestedSubzone()

      // Start date is still in the future.
      helper.insertPlantedSite(timeZone = zone3)
      insertObservation(
          endDate = endDate, startDate = startDate.plusDays(1), state = ObservationState.Upcoming)
      insertObservationRequestedSubzone()

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
      insertObservationRequestedSubzone()

      insertPlantingSite(timeZone = timeZone)
      insertPlantingZone()
      insertPlantingSubzone()
      insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)
      insertObservationRequestedSubzone()

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
    fun `does not returns observations with no requested subzones`() {
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
    fun `only returns observations with requested subzones`() {
      val timeZone = ZoneId.of("America/Denver")
      val startDate = LocalDate.of(2023, 4, 1)
      val endDate = LocalDate.of(2023, 4, 30)

      val now = ZonedDateTime.of(startDate, LocalTime.MIDNIGHT, timeZone).toInstant()
      clock.instant = now

      helper.insertPlantedSite(timeZone = timeZone)
      val startableObservationId =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)
      insertObservationRequestedSubzone()

      // Another planting site with no requested subzones.
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
      insertObservationRequestedSubzone()

      // Start date plus 1 month is an hour ago.
      helper.insertPlantedSite(timeZone = zone3)
      val observationId2 =
          insertObservation(
              endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)
      insertObservationRequestedSubzone()

      // Start date plus 1 month hasn't arrived yet in the site's time zone.
      helper.insertPlantedSite(timeZone = zone1)
      insertObservation(endDate = endDate, startDate = startDate, state = ObservationState.Upcoming)
      insertObservationRequestedSubzone()

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
                  isAdHoc = false,
                  observationType = ObservationType.Monitoring,
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
              isAdHoc = false,
              observationTypeId = ObservationType.Monitoring,
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
                isAdHoc = false,
                observationType = ObservationType.Monitoring,
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
                isAdHoc = false,
                observationType = ObservationType.Monitoring,
                plantingSiteId = plantingSiteId,
                startDate = LocalDate.EPOCH,
                state = ObservationState.Upcoming,
            ))
      }
    }

    @Test
    fun `throws exception if no permission for ad-hoc observation`() {
      every { user.canScheduleAdHocObservation(plantingSiteId) } returns false

      assertThrows<AccessDeniedException> {
        store.createObservation(
            NewObservationModel(
                endDate = LocalDate.EPOCH,
                id = null,
                isAdHoc = true,
                observationType = ObservationType.Monitoring,
                plantingSiteId = plantingSiteId,
                startDate = LocalDate.EPOCH,
                state = ObservationState.Upcoming,
            ))
      }
    }

    @Test
    fun `throws exception for ad-hoc observation with requested subzones`() {
      insertPlantingZone()
      val subzoneId1 = insertPlantingSubzone()
      val subzoneId2 = insertPlantingSubzone()
      assertThrows<IllegalArgumentException> {
        store.createObservation(
            NewObservationModel(
                endDate = LocalDate.EPOCH,
                id = null,
                isAdHoc = true,
                observationType = ObservationType.Monitoring,
                plantingSiteId = plantingSiteId,
                requestedSubzoneIds = setOf(subzoneId1, subzoneId2),
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

      // at least one completed plot is required for Completed state.
      val plotId = insertMonitoringPlot()
      insertObservationPlot(
          observationId = observationId,
          monitoringPlotId = plotId,
          claimedBy = currentUser().userId,
          claimedTime = Instant.EPOCH,
          completedBy = currentUser().userId,
          completedTime = Instant.ofEpochSecond(6000),
          statusId = ObservationPlotStatus.Completed)

      store.updateObservationState(observationId, ObservationState.Completed)

      assertEquals(
          initial.copy(
              completedTime = Instant.ofEpochSecond(6000), state = ObservationState.Completed),
          store.fetchObservationById(observationId))
    }

    @Test
    fun `throws exception if observation has no completed plot when updating to Completed`() {
      val observationId = insertObservation()

      every { user.canManageObservation(observationId) } returns false

      val plotId = insertMonitoringPlot()
      insertObservationPlot(
          observationId = observationId,
          monitoringPlotId = plotId,
          claimedBy = currentUser().userId,
          claimedTime = Instant.EPOCH,
          statusId = ObservationPlotStatus.Claimed)

      assertThrows<IllegalStateException> {
        store.updateObservationState(observationId, ObservationState.Completed)
      }
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
    fun `throws exception if setting state to InProgress`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)

      assertThrows<IllegalArgumentException> {
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
      val earlierCompletedPlotId = insertMonitoringPlot()
      val laterCompletedPlotId = insertMonitoringPlot()
      val unclaimedPlotId = insertMonitoringPlot()
      val claimedPlotId = insertMonitoringPlot()

      val observationId = insertObservation()

      insertObservationPlot(
          observationId = observationId,
          monitoringPlotId = earlierCompletedPlotId,
          claimedBy = currentUser().userId,
          claimedTime = Instant.EPOCH,
          completedBy = currentUser().userId,
          completedTime = Instant.ofEpochSecond(6000),
          statusId = ObservationPlotStatus.Completed)

      insertObservationPlot(
          observationId = observationId,
          monitoringPlotId = laterCompletedPlotId,
          claimedBy = currentUser().userId,
          claimedTime = Instant.EPOCH,
          completedBy = currentUser().userId,
          completedTime = Instant.ofEpochSecond(12000),
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
      val earlierCompletedRow = plotsRows[earlierCompletedPlotId]!!
      val laterCompletedRow = plotsRows[laterCompletedPlotId]!!
      val unclaimedRow = plotsRows[unclaimedPlotId]!!
      val claimedRow = plotsRows[claimedPlotId]!!

      clock.instant = Instant.ofEpochSecond(500)

      store.abandonObservation(observationId)

      assertEquals(
          setOf(
              earlierCompletedRow,
              laterCompletedRow,
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
          existing.copy(
              completedTime = Instant.ofEpochSecond(12000), stateId = ObservationState.Abandoned),
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
      store.populateCumulativeDead(observationId2)

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
    fun `does not update zone or site species totals for ad-hoc observation`() {
      val gpsCoordinates = point(1)
      val speciesId = insertSpecies()

      monitoringPlotId = insertMonitoringPlot(plantingSubzoneId = null, isAdHoc = true)
      val observationId1 = insertObservation(isAdHoc = true)
      insertObservationPlot(claimedBy = user.userId, isPermanent = false)

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
                  mortalityRate = null,
                  cumulativeDead = 0,
                  permanentLive = 0,
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
                  mortalityRate = null,
                  cumulativeDead = 0,
                  permanentLive = 0,
              ),
          )

      assertTableEquals(expectedPlotsBeforeMerge, "Before merge")
      assertTableEmpty(OBSERVED_ZONE_SPECIES_TOTALS)
      assertTableEmpty(OBSERVED_SITE_SPECIES_TOTALS)

      store.mergeOtherSpecies(observationId1, "Merge", speciesId)

      val expectedPlotsAfterMerge =
          listOf(
              expectedPlotsBeforeMerge[0].apply {
                totalLive = 2
                totalDead = 1
              },
              // expectedPlotsBeforeMerge[1] should be deleted
          )

      assertTableEquals(expectedPlotsAfterMerge, "After merge")
      assertTableEmpty(OBSERVED_ZONE_SPECIES_TOTALS)
      assertTableEmpty(OBSERVED_SITE_SPECIES_TOTALS)
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
  inner class AddAdHocPlotToObservation {
    @Test
    fun `inserts a non-permanent observation plot`() {
      insertPlantingZone()
      insertPlantingSubzone()
      val plotId = insertMonitoringPlot(isAdHoc = true)
      val observationId = insertObservation(isAdHoc = true)

      store.addAdHocPlotToObservation(observationId, plotId)

      assertEquals(
          ObservationPlotsRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              createdBy = user.userId,
              createdTime = clock.instant,
              isPermanent = false,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              statusId = ObservationPlotStatus.Unclaimed,
              monitoringPlotHistoryId = inserted.monitoringPlotHistoryId,
          ),
          observationPlotsDao
              .fetchByObservationPlotId(ObservationPlotId(observationId, plotId))
              .single(),
          "Observation plot row")
    }

    @Test
    fun `throws exception if plots belong to a different planting site`() {
      val observationId = insertObservation(isAdHoc = true)

      insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      val otherSitePlotId = insertMonitoringPlot(isAdHoc = true)

      assertThrows<IllegalStateException> {
        store.addAdHocPlotToObservation(observationId, otherSitePlotId)
      }
    }

    @Test
    fun `throws exception for a non-ad-hoc observation`() {
      val observationId = insertObservation(isAdHoc = false)

      insertPlantingZone()
      insertPlantingSubzone()
      val plotId = insertMonitoringPlot(isAdHoc = true)

      assertThrows<IllegalStateException> { store.addAdHocPlotToObservation(observationId, plotId) }
    }

    @Test
    fun `throws exception for a non-ad-hoc plot`() {
      val observationId = insertObservation(isAdHoc = true)

      insertPlantingZone()
      insertPlantingSubzone()
      val plotId = insertMonitoringPlot(isAdHoc = false)

      assertThrows<IllegalStateException> { store.addAdHocPlotToObservation(observationId, plotId) }
    }

    @Test
    fun `throws exception if no permission to schedule ad-hoc observation`() {
      every { user.canScheduleAdHocObservation(plantingSiteId) } returns false

      val observationId = insertObservation(isAdHoc = true)

      insertPlantingZone()
      insertPlantingSubzone()
      val plotId = insertMonitoringPlot(isAdHoc = false)

      assertThrows<AccessDeniedException> { store.addAdHocPlotToObservation(observationId, plotId) }
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
    fun `throws exception for an ad-hoc observation`() {
      val observationId = insertObservation(isAdHoc = true)

      insertPlantingZone()
      insertPlantingSubzone()
      val plotId = insertMonitoringPlot()

      assertThrows<IllegalStateException> {
        store.addPlotsToObservation(observationId, listOf(plotId), true)
      }
    }

    @Test
    fun `throws exception for an an-hoc plot`() {
      val observationId = insertObservation()

      insertPlantingZone()
      insertPlantingSubzone()
      val plotId = insertMonitoringPlot(isAdHoc = true)

      assertThrows<IllegalStateException> {
        store.addPlotsToObservation(observationId, listOf(plotId), true)
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

      assertEquals(
          observedTime,
          plantingSubzonesDao.fetchOneById(inserted.plantingSubzoneId)?.observedTime,
          "Subzone observed time")
    }

    @Test
    fun `updates observed species totals`() {
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()
      val speciesId3 = insertSpecies()
      insertObservationPlot(
          claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)
      val zoneId1 = inserted.plantingZoneId
      val zone1SubzoneId1 = inserted.plantingSubzoneId
      val zone1PlotId2 = insertMonitoringPlot()
      insertObservationPlot(
          claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = false)
      val zoneId2 = insertPlantingZone()
      val zone2SubzoneId1 = insertPlantingSubzone()
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
      var zone1Subzone1Species1Totals =
          ObservedSubzoneSpeciesTotalsRow(
              observationId, zone1SubzoneId1, speciesId1, null, Known, 2, 1, 1, 33, 1, 2)
      val zone1Subzone1Species2Totals =
          ObservedSubzoneSpeciesTotalsRow(
              observationId, zone1SubzoneId1, speciesId2, null, Known, 0, 1, 0, 100, 1, 0)
      var zone1Subzone1Species3Totals =
          ObservedSubzoneSpeciesTotalsRow(
              observationId, zone1SubzoneId1, speciesId3, null, Known, 0, 0, 1, 0, 0, 0)
      val zone1Subzone1Other1Totals =
          ObservedSubzoneSpeciesTotalsRow(
              observationId, zone1SubzoneId1, null, "Other 1", Other, 1, 1, 0, 50, 1, 1)
      val zone1Subzone1Other2Totals =
          ObservedSubzoneSpeciesTotalsRow(
              observationId, zone1SubzoneId1, null, "Other 2", Other, 1, 0, 0, 0, 0, 1)
      var zone1Subzone1UnknownTotals =
          ObservedSubzoneSpeciesTotalsRow(
              observationId, zone1SubzoneId1, null, null, Unknown, 1, 0, 0, 0, 0, 1)

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
              zone1Subzone1Other1Totals,
              zone1Subzone1Other2Totals,
              zone1Subzone1Species1Totals,
              zone1Subzone1Species2Totals,
              zone1Subzone1Species3Totals,
              zone1Subzone1UnknownTotals,
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
      zone1Subzone1Species1Totals = zone1Subzone1Species1Totals.copy(totalLive = 3)
      zone1Subzone1Species3Totals = zone1Subzone1Species3Totals.copy(totalExisting = 2)
      zone1Subzone1UnknownTotals = zone1Subzone1UnknownTotals.copy(totalLive = 2)

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
              zone1Subzone1Other1Totals,
              zone1Subzone1Other2Totals,
              zone1Subzone1Species1Totals,
              zone1Subzone1Species2Totals,
              zone1Subzone1Species3Totals,
              zone1Subzone1UnknownTotals,
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
      val zone2Subzone1Species1Totals =
          ObservedSubzoneSpeciesTotalsRow(
              observationId, zone2SubzoneId1, speciesId1, null, Known, 0, 1, 1, 100, 1, 0)
      val zone2Subzone1Other1Totals =
          ObservedSubzoneSpeciesTotalsRow(
              observationId, zone2SubzoneId1, null, "Other 1", Other, 1, 0, 0, 0, 0, 1)
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
              zone1Subzone1Other1Totals,
              zone1Subzone1Other2Totals,
              zone1Subzone1Species1Totals,
              zone1Subzone1Species2Totals,
              zone1Subzone1Species3Totals,
              zone1Subzone1UnknownTotals,
              zone1UnknownTotals,
              zone1Species3Totals,
              zone2Other1Totals,
              zone2Plot1Other1Totals,
              zone2Plot1Species1Totals,
              zone2Species1Totals,
              zone2Subzone1Other1Totals,
              zone2Subzone1Species1Totals,
          ),
          "Totals after observation in second zone")

      assertTableEquals(
          PlantingSitePopulationsRecord(plantingSiteId, inserted.speciesId, 3, 3),
          "Planting site total populations should be unchanged")

      assertTableEquals(
          PlantingZonePopulationsRecord(inserted.plantingZoneId, inserted.speciesId, 2, 2),
          "Planting zone total populations should be unchanged")

      assertTableEquals(
          PlantingSubzonePopulationsRecord(inserted.plantingSubzoneId, inserted.speciesId, 1, 1),
          "Planting subzone total populations should be unchanged")
    }

    @Test
    fun `does not update total plants if plant rows are empty`() {
      insertSpecies()
      insertObservationPlot(
          claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)

      insertPlantingSitePopulation(totalPlants = 3, plantsSinceLastObservation = 3)
      insertPlantingZonePopulation(totalPlants = 2, plantsSinceLastObservation = 2)
      insertPlantingSubzonePopulation(totalPlants = 1, plantsSinceLastObservation = 1)

      val observedTime = Instant.ofEpochSecond(1)
      clock.instant = Instant.ofEpochSecond(123)

      store.completePlot(
          observationId,
          plotId,
          setOf(ObservableCondition.AnimalDamage, ObservableCondition.FastGrowth),
          "Notes",
          observedTime,
          emptyList())

      val expectedConditions =
          setOf(
              ObservationPlotConditionsRecord(
                  observationId, plotId, ObservableCondition.AnimalDamage),
              ObservationPlotConditionsRecord(
                  observationId, plotId, ObservableCondition.FastGrowth),
          )

      val newRow =
          observationPlotsDao
              .fetchByObservationPlotId(
                  ObservationPlotId(inserted.observationId, inserted.monitoringPlotId))
              .single()
              .copy(
                  notes = "Notes",
                  observedTime = observedTime,
                  statusId = ObservationPlotStatus.Completed,
              )

      assertTableEquals(ObservationPlotsRecord(newRow), "Updated observation plot entry")
      assertTableEquals(expectedConditions, "Inserted observation plot conditions")
      assertTableEmpty(RECORDED_PLANTS, "No plants recorded")

      assertTableEmpty(OBSERVED_PLOT_SPECIES_TOTALS, "Observed plot species should be empty")
      assertTableEmpty(OBSERVED_SUBZONE_SPECIES_TOTALS, "Observed subzone species should be empty")
      assertTableEmpty(OBSERVED_ZONE_SPECIES_TOTALS, "Observed zone species should be empty")
      assertTableEmpty(OBSERVED_SITE_SPECIES_TOTALS, "Observed site species should be empty")

      assertTableEquals(
          PlantingSitePopulationsRecord(plantingSiteId, inserted.speciesId, 3, 0),
          "Planting site total plants should be unchanged")

      assertTableEquals(
          PlantingZonePopulationsRecord(inserted.plantingZoneId, inserted.speciesId, 2, 0),
          "Planting zone total plants should be unchanged")

      assertTableEquals(
          PlantingSubzonePopulationsRecord(inserted.plantingSubzoneId, inserted.speciesId, 1, 0),
          "Planting subzone total plants should be unchanged")
    }

    @Test
    fun `updates cumulative dead from initial values inserted by populateCumulativeDead`() {
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
      store.populateCumulativeDead(observationId2)

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

    // SW-6717: This can happen if all of a subzone's monitoring plots move to a new subzone
    //          thanks to a map edit; the original subzone will have subzone-level species totals
    //          but we don't want to use them as a starting point for a new observation since
    //          there are no monitoring plots in common.
    @Test
    fun `does not use cumulative dead from past observations if current observation has no total for a species`() {
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

      // We do not call populateCumulativeDead here, so there is no observed subzone species
      // total for this observation even though there's one for the previous observation.

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
          1,
          with(OBSERVED_SUBZONE_SPECIES_TOTALS) {
            dslContext
                .select(CUMULATIVE_DEAD)
                .from(this)
                .where(OBSERVATION_ID.eq(observationId2))
                .fetchOne(CUMULATIVE_DEAD)
          },
          "Subzone cumulative dead for second observation")
      assertEquals(
          1,
          with(OBSERVED_ZONE_SPECIES_TOTALS) {
            dslContext
                .select(CUMULATIVE_DEAD)
                .from(this)
                .where(OBSERVATION_ID.eq(observationId2))
                .fetchOne(CUMULATIVE_DEAD)
          },
          "Zone cumulative dead for second observation")
      assertEquals(
          1,
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
              ObservedSubzoneSpeciesTotalsRow(
                  observationId = observationId,
                  plantingSubzoneId = inserted.plantingSubzoneId,
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
      fun `returns correct counts without counting ad-hoc plots`() {
        val plotId1 = insertMonitoringPlot()
        val plotId2 = insertMonitoringPlot()
        val plotId3 = insertMonitoringPlot()
        val plotId4 = insertMonitoringPlot()
        val plotId5 = insertMonitoringPlot()
        val plotId6 = insertMonitoringPlot()
        val adHocPlotId = insertMonitoringPlot(isAdHoc = true)

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

        val adHocObservationId = insertObservation(isAdHoc = true)
        insertObservationPlot(observationId = adHocObservationId, monitoringPlotId = adHocPlotId)

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

        assertEquals(expected, actual, "counting non-ad-hoc")

        assertEquals(
            mapOf(
                adHocObservationId to
                    ObservationPlotCounts(
                        totalIncomplete = 1,
                        totalPlots = 1,
                        totalUnclaimed = 1,
                    )),
            store.countPlots(plantingSiteId, true),
            "counting ad-hoc")
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
      val zone1SubzoneId1 = insertPlantingSubzone()
      val zone1PlotId1 = insertMonitoringPlot()
      val zone1PlotId2 = insertMonitoringPlot()
      val zoneId2 = insertPlantingZone()
      val zone2SubzoneId1 = insertPlantingSubzone()
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
              ObservedSubzoneSpeciesTotalsRow(
                  observationId1, zone1SubzoneId1, speciesId1, null, Known, 0, 0, 0, 0, 0, 0),
              ObservedSubzoneSpeciesTotalsRow(
                  observationId1, zone1SubzoneId1, speciesId2, null, Known, 1, 1, 0, 50, 1, 1),
              ObservedSubzoneSpeciesTotalsRow(
                  observationId1, zone1SubzoneId1, null, "fern", Other, 0, 0, 0, 0, 0, 0),
              ObservedSubzoneSpeciesTotalsRow(
                  observationId1, zone2SubzoneId1, speciesId1, null, Known, 1, 0, 0, 0, 0, 1),
              ObservedSubzoneSpeciesTotalsRow(
                  observationId1, zone2SubzoneId1, speciesId3, null, Known, 1, 0, 0, 0, 0, 1),
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
              ObservedSubzoneSpeciesTotalsRow(
                  observationId2, zone1SubzoneId1, speciesId1, null, Known, 0, 0, 0, 0, 0, 0),
              ObservedSubzoneSpeciesTotalsRow(
                  observationId2, zone1SubzoneId1, speciesId2, null, Known, 1, 0, 0, 50, 1, 1),
              ObservedSubzoneSpeciesTotalsRow(
                  observationId2, zone1SubzoneId1, null, "fern", Other, 0, 0, 0, 0, 0, 0),
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

    @Test
    fun `throws exception if removing plot totals for an ad-hoc plot`() {
      insertPlantingSite()
      val plotId = insertMonitoringPlot(isAdHoc = true)

      assertThrows<IllegalStateException> { store.removePlotFromTotals(plotId) }
    }
  }

  @Nested
  inner class InsertBiomassDetails {
    private lateinit var observationId: ObservationId
    private lateinit var plotId: MonitoringPlotId

    @BeforeEach
    fun setUp() {
      plotId = insertMonitoringPlot(isAdHoc = true)
      observationId =
          insertObservation(isAdHoc = true, observationType = ObservationType.BiomassMeasurements)
      insertObservationPlot(claimedBy = currentUser().userId, claimedTime = clock.instant)
    }

    @Test
    fun `inserts required biomass detail, quadrant species and details, trees and branches`() {
      val herbaceousSpeciesId1 = insertSpecies()
      val herbaceousSpeciesId2 = insertSpecies()

      val treeSpeciesId1 = insertSpecies()
      val treeSpeciesId2 = insertSpecies()

      val model =
          NewBiomassDetailsModel(
              description = "description",
              forestType = BiomassForestType.Mangrove,
              herbaceousCoverPercent = 10,
              observationId = null,
              ph = BigDecimal.valueOf(6.5),
              quadrats =
                  mapOf(
                      ObservationPlotPosition.NortheastCorner to
                          BiomassQuadratModel(
                              description = "NE description",
                              species =
                                  setOf(
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 40,
                                          speciesId = herbaceousSpeciesId1,
                                      ))),
                      ObservationPlotPosition.NorthwestCorner to
                          BiomassQuadratModel(
                              description = "NW description",
                              species =
                                  setOf(
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 60,
                                          speciesId = herbaceousSpeciesId2,
                                      ),
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 5,
                                          speciesName = "Other herbaceous species",
                                      ),
                                  )),
                      ObservationPlotPosition.SoutheastCorner to
                          BiomassQuadratModel(
                              description = "SE description",
                              species =
                                  setOf(
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 90,
                                          speciesId = herbaceousSpeciesId1,
                                      ))),
                      ObservationPlotPosition.SouthwestCorner to
                          BiomassQuadratModel(
                              description = "SW description",
                              species = emptySet(),
                          ),
                  ),
              salinityPpt = BigDecimal.valueOf(20),
              smallTreeCountRange = 0 to 10,
              soilAssessment = "soil",
              species =
                  setOf(
                      BiomassSpeciesModel(
                          speciesId = herbaceousSpeciesId1,
                          isInvasive = false,
                          isThreatened = false,
                      ),
                      BiomassSpeciesModel(
                          speciesId = herbaceousSpeciesId2,
                          isInvasive = false,
                          isThreatened = true,
                      ),
                      BiomassSpeciesModel(
                          scientificName = "Other herbaceous species",
                          commonName = "Common herb",
                          isInvasive = true,
                          isThreatened = false,
                      ),
                      BiomassSpeciesModel(
                          speciesId = treeSpeciesId1,
                          isInvasive = false,
                          isThreatened = true,
                      ),
                      BiomassSpeciesModel(
                          speciesId = treeSpeciesId2,
                          isInvasive = false,
                          isThreatened = false,
                      ),
                      BiomassSpeciesModel(
                          scientificName = "Other tree species",
                          commonName = "Common tree",
                          isInvasive = false,
                          isThreatened = false,
                      ),
                  ),
              plotId = null,
              tide = MangroveTide.High,
              tideTime = Instant.ofEpochSecond(123),
              trees =
                  listOf(
                      NewRecordedTreeModel(
                          id = null,
                          isDead = false,
                          diameterAtBreastHeightCm = BigDecimal.TWO, // this value is ignored
                          pointOfMeasurementM = BigDecimal.valueOf(1.3), // ignored
                          shrubDiameterCm = 25,
                          speciesId = treeSpeciesId1,
                          treeGrowthForm = TreeGrowthForm.Shrub,
                          treeNumber = 1,
                          trunkNumber = 1,
                      ),
                      NewRecordedTreeModel(
                          id = null,
                          isDead = true,
                          diameterAtBreastHeightCm = BigDecimal.TWO,
                          pointOfMeasurementM = BigDecimal.valueOf(1.3),
                          heightM = BigDecimal.TEN,
                          shrubDiameterCm = 1, // ignored
                          speciesName = "Other tree species",
                          treeGrowthForm = TreeGrowthForm.Tree,
                          treeNumber = 2,
                          trunkNumber = 1,
                      ),
                      NewRecordedTreeModel(
                          id = null,
                          isDead = false,
                          diameterAtBreastHeightCm = BigDecimal.TEN,
                          pointOfMeasurementM = BigDecimal.valueOf(1.5),
                          heightM = BigDecimal.TEN,
                          speciesId = treeSpeciesId2,
                          treeGrowthForm = TreeGrowthForm.Trunk,
                          treeNumber = 3,
                          trunkNumber = 1,
                      ),
                      NewRecordedTreeModel(
                          id = null,
                          diameterAtBreastHeightCm = BigDecimal.TWO,
                          pointOfMeasurementM = BigDecimal.valueOf(1.1),
                          isDead = false,
                          speciesId = treeSpeciesId2,
                          treeGrowthForm = TreeGrowthForm.Trunk,
                          treeNumber = 3,
                          trunkNumber = 2,
                      )),
              waterDepthCm = 2,
          )

      store.insertBiomassDetails(observationId, plotId, model)

      assertTableEquals(
          ObservationBiomassDetailsRecord(
              observationId = observationId,
              monitoringPlotId = plotId,
              description = "description",
              forestTypeId = BiomassForestType.Mangrove,
              herbaceousCoverPercent = 10,
              ph = BigDecimal.valueOf(6.5),
              salinityPpt = BigDecimal.valueOf(20),
              smallTreesCountHigh = 10,
              smallTreesCountLow = 0,
              soilAssessment = "soil",
              tideId = MangroveTide.High,
              tideTime = Instant.ofEpochSecond(123),
              waterDepthCm = 2,
          ),
          "Biomass details table")

      val biomassSpeciesIdsBySpeciesKey =
          observationBiomassSpeciesDao.findAll().associate {
            BiomassSpeciesKey(it.speciesId, it.scientificName) to it.id
          }

      val biomassHerbaceousSpeciesId1 =
          biomassSpeciesIdsBySpeciesKey[BiomassSpeciesKey(speciesId = herbaceousSpeciesId1)]
      val biomassHerbaceousSpeciesId2 =
          biomassSpeciesIdsBySpeciesKey[BiomassSpeciesKey(speciesId = herbaceousSpeciesId2)]
      val biomassHerbaceousSpeciesId3 =
          biomassSpeciesIdsBySpeciesKey[
              BiomassSpeciesKey(scientificName = "Other herbaceous species")]
      val biomassTreeSpeciesId1 =
          biomassSpeciesIdsBySpeciesKey[BiomassSpeciesKey(speciesId = treeSpeciesId1)]
      val biomassTreeSpeciesId2 =
          biomassSpeciesIdsBySpeciesKey[BiomassSpeciesKey(speciesId = treeSpeciesId2)]
      val biomassTreeSpeciesId3 =
          biomassSpeciesIdsBySpeciesKey[BiomassSpeciesKey(scientificName = "Other tree species")]

      assertTableEquals(
          setOf(
              ObservationBiomassSpeciesRecord(
                  id = biomassHerbaceousSpeciesId1,
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  speciesId = herbaceousSpeciesId1,
                  isInvasive = false,
                  isThreatened = false,
              ),
              ObservationBiomassSpeciesRecord(
                  id = biomassHerbaceousSpeciesId2,
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  speciesId = herbaceousSpeciesId2,
                  isInvasive = false,
                  isThreatened = true,
              ),
              ObservationBiomassSpeciesRecord(
                  id = biomassHerbaceousSpeciesId3,
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  scientificName = "Other herbaceous species",
                  commonName = "Common herb",
                  isInvasive = true,
                  isThreatened = false,
              ),
              ObservationBiomassSpeciesRecord(
                  id = biomassTreeSpeciesId1,
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  speciesId = treeSpeciesId1,
                  isInvasive = false,
                  isThreatened = true,
              ),
              ObservationBiomassSpeciesRecord(
                  id = biomassTreeSpeciesId2,
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  speciesId = treeSpeciesId2,
                  isInvasive = false,
                  isThreatened = false,
              ),
              ObservationBiomassSpeciesRecord(
                  id = biomassTreeSpeciesId3,
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  scientificName = "Other tree species",
                  commonName = "Common tree",
                  isInvasive = false,
                  isThreatened = false,
              ),
          ),
          "Biomass species table")

      assertTableEquals(
          setOf(
              ObservationBiomassQuadratDetailsRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.NortheastCorner,
                  description = "NE description"),
              ObservationBiomassQuadratDetailsRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.NorthwestCorner,
                  description = "NW description"),
              ObservationBiomassQuadratDetailsRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.SoutheastCorner,
                  description = "SE description"),
              ObservationBiomassQuadratDetailsRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.SouthwestCorner,
                  description = "SW description"),
          ),
          "Biomass quadrat details table")

      assertTableEquals(
          listOf(
              ObservationBiomassQuadratSpeciesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.NortheastCorner,
                  abundancePercent = 40,
                  biomassSpeciesId = biomassHerbaceousSpeciesId1,
              ),
              ObservationBiomassQuadratSpeciesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.NorthwestCorner,
                  abundancePercent = 60,
                  biomassSpeciesId = biomassHerbaceousSpeciesId2,
              ),
              ObservationBiomassQuadratSpeciesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.NorthwestCorner,
                  abundancePercent = 5,
                  biomassSpeciesId = biomassHerbaceousSpeciesId3,
              ),
              ObservationBiomassQuadratSpeciesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = ObservationPlotPosition.SoutheastCorner,
                  abundancePercent = 90,
                  biomassSpeciesId = biomassHerbaceousSpeciesId1,
              ),
          ),
          "Biomass quadrat species table")

      assertTableEquals(
          listOf(
              RecordedTreesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  biomassSpeciesId = biomassTreeSpeciesId1,
                  isDead = false,
                  shrubDiameterCm = 25,
                  treeGrowthFormId = TreeGrowthForm.Shrub,
                  treeNumber = 1,
                  trunkNumber = 1,
              ),
              RecordedTreesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  biomassSpeciesId = biomassTreeSpeciesId3,
                  diameterAtBreastHeightCm = BigDecimal.TWO,
                  heightM = BigDecimal.TEN,
                  pointOfMeasurementM = BigDecimal.valueOf(1.3),
                  isDead = true,
                  treeGrowthFormId = TreeGrowthForm.Tree,
                  treeNumber = 2,
                  trunkNumber = 1,
              ),
              RecordedTreesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  biomassSpeciesId = biomassTreeSpeciesId2,
                  diameterAtBreastHeightCm = BigDecimal.TEN,
                  pointOfMeasurementM = BigDecimal.valueOf(1.5),
                  heightM = BigDecimal.TEN,
                  isDead = false,
                  treeGrowthFormId = TreeGrowthForm.Trunk,
                  treeNumber = 3,
                  trunkNumber = 1,
              ),
              RecordedTreesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  biomassSpeciesId = biomassTreeSpeciesId2,
                  diameterAtBreastHeightCm = BigDecimal.TWO,
                  pointOfMeasurementM = BigDecimal.valueOf(1.1),
                  isDead = false,
                  treeGrowthFormId = TreeGrowthForm.Trunk,
                  treeNumber = 3,
                  trunkNumber = 2,
              ),
          ),
          "Recorded trees table")
    }

    @Test
    fun `throws exception if no permission`() {
      val model =
          NewBiomassDetailsModel(
              description = "Basic biomass details",
              forestType = BiomassForestType.Terrestrial,
              herbaceousCoverPercent = 0,
              observationId = null,
              smallTreeCountRange = 0 to 0,
              soilAssessment = "Basic soil assessment",
              plotId = null,
          )

      every { user.canUpdateObservation(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.insertBiomassDetails(observationId, plotId, model)
      }

      every { user.canReadObservation(any()) } returns false

      assertThrows<ObservationNotFoundException> {
        store.insertBiomassDetails(observationId, plotId, model)
      }
    }
  }
}
