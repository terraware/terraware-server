package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPhotoPosition
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPhotosRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.InvalidObservationEndDateException
import com.terraformation.backend.tracking.db.InvalidObservationStartDateException
import com.terraformation.backend.tracking.db.ObservationAlreadyStartedException
import com.terraformation.backend.tracking.db.ObservationHasNoPlotsException
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import com.terraformation.backend.tracking.db.ObservationRescheduleStateException
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.db.PlotAlreadyCompletedException
import com.terraformation.backend.tracking.db.PlotNotInObservationException
import com.terraformation.backend.tracking.db.ScheduleObservationWithoutPlantsException
import com.terraformation.backend.tracking.event.ObservationPlotReplacedEvent
import com.terraformation.backend.tracking.event.ObservationRescheduledEvent
import com.terraformation.backend.tracking.event.ObservationScheduledEvent
import com.terraformation.backend.tracking.event.ObservationStartedEvent
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NotificationCriteria
import com.terraformation.backend.tracking.model.ReplacementDuration
import com.terraformation.backend.tracking.model.ReplacementResult
import com.terraformation.backend.tracking.model.ReplacementResultPlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

class ObservationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = spyk(TestClock())
  private val eventPublisher = TestEventPublisher()
  private val fileStore: FileStore = mockk()
  private val terrawareServerConfig: TerrawareServerConfig = mockk()
  private val thumbnailStore: ThumbnailStore = mockk()
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val fileService: FileService by lazy {
    FileService(
        dslContext, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), filesDao, fileStore, thumbnailStore)
  }
  private val observationStore: ObservationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        recordedPlantsDao)
  }
  private val plantingSiteStore: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        dslContext,
        TestEventPublisher(),
        monitoringPlotsDao,
        parentStore,
        plantingSitesDao,
        plantingSubzonesDao,
        plantingZonesDao)
  }

  private val service: ObservationService by lazy {
    ObservationService(
        clock,
        eventPublisher,
        fileService,
        observationPhotosDao,
        observationStore,
        plantingSiteStore,
        parentStore,
        terrawareServerConfig)
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
    every { user.canReadPlantingZone(any()) } returns true
    every { user.canRescheduleObservation(any()) } returns true
    every { user.canScheduleObservation(any()) } returns true
    every { user.canUpdateObservation(any()) } returns true
  }

  @Nested
  inner class StartObservation {
    @Test
    fun `assigns correct plots to planting zones`() {
      // Given a planting site with this structure:
      //
      // Zone 1 (2 permanent, 3 temporary)
      //   Subzone 1 (has plants)
      //     Plot A - permanent cluster 1
      //     Plot B - permanent cluster 1
      //     Plot C - permanent cluster 1
      //     Plot D - permanent cluster 1
      //     Plot E - permanent cluster 3
      //     Plot F - permanent cluster 3
      //     Plot G - permanent cluster 3
      //     Plot H - permanent cluster 3
      //     Plot I
      //     Plot J
      //   Subzone 2 (no plants)
      //     Plot K - permanent cluster 2
      //     Plot L - permanent cluster 2
      //     Plot M - permanent cluster 2
      //     Plot N - permanent cluster 2
      //     Plot O
      //     Plot P
      // Zone 2 (2 permanent, 2 temporary)
      //   Subzone 3 (no plants)
      //     Plot Q - permanent 1
      //
      // We should get:
      // - Plots A-D because they are the first permanent cluster in the zone and they lie in a
      //   planted subzone. They should be selected as permanent plots.
      // - Exactly one of plots E-J as a temporary plot. The zone is configured for 3 temporary
      //   plots. 2 of them are spread evenly across the 2 subzones, and the remaining one is placed
      //   in the subzone with the fewest permanent plots, which is subzone 2, but subzone 2's plots
      //   are excluded because it has no plants.
      // - Nothing from zone 2 because it has no plants.

      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()
      val zone1PermanentCluster1 =
          setOf(
              insertMonitoringPlot(permanentCluster = 1),
              insertMonitoringPlot(permanentCluster = 1),
              insertMonitoringPlot(permanentCluster = 1),
              insertMonitoringPlot(permanentCluster = 1),
          )
      val zone1PermanentCluster3 =
          setOf(
              insertMonitoringPlot(permanentCluster = 3),
              insertMonitoringPlot(permanentCluster = 3),
              insertMonitoringPlot(permanentCluster = 3),
              insertMonitoringPlot(permanentCluster = 3),
          )
      val zone1NonPermanent =
          setOf(
              insertMonitoringPlot(),
              insertMonitoringPlot(),
          )

      insertPlantingSubzone()
      insertMonitoringPlot(permanentCluster = 2)
      insertMonitoringPlot()
      insertMonitoringPlot()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 2)
      insertPlantingSubzone()
      insertMonitoringPlot(permanentCluster = 1)

      val observationId = insertObservation(state = ObservationState.Upcoming)

      service.startObservation(observationId)

      val observationPlots = observationPlotsDao.findAll()

      assertEquals(5, observationPlots.size, "Should have selected 2 plots")
      assertEquals(
          zone1PermanentCluster1,
          observationPlots.filter { it.isPermanent!! }.map { it.monitoringPlotId }.toSet(),
          "Permanent plot IDs")
      assertEquals(
          1,
          observationPlots
              .filter { !it.isPermanent!! }
              .map { it.monitoringPlotId }
              .count { it in (zone1NonPermanent + zone1PermanentCluster3) },
          "Should have selected one temporary plot")
      assertEquals(
          ObservationState.InProgress,
          observationsDao.fetchOneById(observationId)!!.stateId,
          "Observation state")

      eventPublisher.assertExactEventsPublished(
          setOf(ObservationStartedEvent(observationStore.fetchObservationById(observationId))))
    }

    @Test
    fun `throws exception if observation already started`() {
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      val observationId = insertObservation(state = ObservationState.InProgress)

      assertThrows<ObservationAlreadyStartedException> { service.startObservation(observationId) }
    }

    @Test
    fun `throws exception if observation already has plots assigned`() {
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      val observationId = insertObservation(state = ObservationState.Upcoming)

      insertObservationPlot()

      assertThrows<ObservationAlreadyStartedException> { service.startObservation(observationId) }
    }

    @Test
    fun `throws exception if planting site has no planted subzones`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)

      assertThrows<ObservationHasNoPlotsException> { service.startObservation(observationId) }
    }

    @Test
    fun `throws exception if no permission to manage observation`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)

      every { user.canManageObservation(observationId) } returns false

      assertThrows<AccessDeniedException> { service.startObservation(observationId) }
    }
  }

  @Nested
  inner class Photos {
    private lateinit var observationId: ObservationId
    private lateinit var plotId: MonitoringPlotId
    private var storageUrlCount = 0

    private val metadata = FileMetadata.of(MediaType.IMAGE_JPEG_VALUE, "filename", 1L)

    @BeforeEach
    fun setUp() {
      every { fileStore.newUrl(any(), any(), any()) } answers { URI("${++storageUrlCount}") }
      every { fileStore.write(any(), any()) } just Runs

      insertPlantingZone()
      insertPlantingSubzone()
      plotId = insertMonitoringPlot()
      observationId = insertObservation()
      insertObservationPlot()
    }

    @Nested
    inner class ReadPhoto {
      private val content = byteArrayOf(1, 2, 3, 4)

      private lateinit var fileId: FileId

      @BeforeEach
      fun setUp() {
        fileId =
            service.storePhoto(
                observationId,
                plotId,
                point(1.0),
                ObservationPhotoPosition.NortheastCorner,
                content.inputStream(),
                metadata)
      }

      @Test
      fun `returns photo data`() {
        every { fileStore.read(URI("1")) } returns SizedInputStream(content.inputStream(), 4L)

        val inputStream = service.readPhoto(observationId, plotId, fileId)
        assertArrayEquals(content, inputStream.readAllBytes())
      }

      @Test
      fun `returns thumbnail data`() {
        val maxWidth = 40
        val maxHeight = 30
        val thumbnailContent = byteArrayOf(9, 8, 7)

        every { thumbnailStore.getThumbnailData(fileId, maxWidth, maxHeight) } returns
            SizedInputStream(thumbnailContent.inputStream(), 3)

        val inputStream = service.readPhoto(observationId, plotId, fileId, maxWidth, maxHeight)
        assertArrayEquals(thumbnailContent, inputStream.readAllBytes())
      }

      @Test
      fun `throws exception if photo is from wrong observation`() {
        val otherObservationId = insertObservation()
        insertObservationPlot()

        assertThrows<FileNotFoundException> {
          service.readPhoto(otherObservationId, plotId, fileId)
        }
      }

      @Test
      fun `throws exception if photo is from wrong monitoring plot`() {
        val otherPlotId = insertMonitoringPlot()
        insertObservationPlot()

        assertThrows<FileNotFoundException> {
          service.readPhoto(observationId, otherPlotId, fileId)
        }
      }

      @Test
      fun `throws exception if no permission to read observation`() {
        every { user.canReadObservation(observationId) } returns false

        assertThrows<ObservationNotFoundException> {
          service.readPhoto(observationId, plotId, fileId)
        }
      }
    }

    @Nested
    inner class StorePhoto {
      @Test
      fun `associates photo with observation and plot`() {
        val fileId =
            service.storePhoto(
                observationId,
                plotId,
                point(1.0),
                ObservationPhotoPosition.NortheastCorner,
                byteArrayOf(1).inputStream(),
                metadata)

        verify { fileStore.write(any(), any()) }

        assertEquals(
            listOf(
                ObservationPhotosRow(
                    fileId,
                    observationId,
                    plotId,
                    ObservationPhotoPosition.NortheastCorner,
                    point(1.0))),
            observationPhotosDao.findAll())
      }

      @Test
      fun `throws exception if no permission to update observation`() {
        every { user.canUpdateObservation(observationId) } returns false

        assertThrows<AccessDeniedException> {
          service.storePhoto(
              observationId,
              plotId,
              point(1.0),
              ObservationPhotoPosition.NortheastCorner,
              byteArrayOf(1).inputStream(),
              metadata)
        }
      }
    }
  }

  @Nested
  inner class ScheduleObservation {
    @Test
    fun `throws access denied exception scheduling an observation if no permission to schedule observation`() {
      every { user.canScheduleObservation(plantingSiteId) } returns false

      assertThrows<AccessDeniedException> {
        service.scheduleObservation(newObservationModel(plantingSiteId = plantingSiteId))
      }
    }

    @Test
    fun `throws planting site not found exception scheduling an observation if no permission to schedule observation or read the planting site`() {
      every { user.canReadPlantingSite(plantingSiteId) } returns false
      every { user.canScheduleObservation(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> {
        service.scheduleObservation(newObservationModel(plantingSiteId = plantingSiteId))
      }
    }

    @Test
    fun `throws exception scheduling an observation if start date is in the past`() {
      every { clock.instant() } returns Instant.EPOCH.plus(1, ChronoUnit.DAYS)

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      assertThrows<InvalidObservationStartDateException> {
        service.scheduleObservation(
            newObservationModel(
                plantingSiteId = plantingSiteId, startDate = startDate, endDate = endDate))
      }
    }

    @Test
    fun `throws exception scheduling an observation if start date is more than a year in the future`() {
      val startDate = LocalDate.EPOCH.plusYears(1).plusDays(1)
      val endDate = startDate.plusDays(1)

      assertThrows<InvalidObservationStartDateException> {
        service.scheduleObservation(
            newObservationModel(
                plantingSiteId = plantingSiteId, startDate = startDate, endDate = endDate))
      }
    }

    @Test
    fun `throws exception scheduling an observation if end date is on or before the start date`() {
      val startDate = LocalDate.EPOCH.plusDays(5)
      val endDate = startDate.minusDays(2)

      assertThrows<InvalidObservationEndDateException> {
        service.scheduleObservation(
            newObservationModel(
                plantingSiteId = plantingSiteId, startDate = startDate, endDate = endDate))
      }
    }

    @Test
    fun `throws exception scheduling an observation if end date is more than 2 months after the start date`() {
      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusMonths(2).plusDays(1)

      assertThrows<InvalidObservationEndDateException> {
        service.scheduleObservation(
            newObservationModel(
                plantingSiteId = plantingSiteId, startDate = startDate, endDate = endDate))
      }
    }

    @Test
    fun `throws exception scheduling an observation on a site with no plants in subzones`() {
      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      assertThrows<ScheduleObservationWithoutPlantsException> {
        service.scheduleObservation(
            newObservationModel(
                plantingSiteId = plantingSiteId, startDate = startDate, endDate = endDate))
      }
    }

    @Test
    fun `schedules a new observation for a site with plantings in subzones`() {
      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      val insertedPlantingSiteId = insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      val observationId =
          service.scheduleObservation(
              newObservationModel(
                  plantingSiteId = insertedPlantingSiteId,
                  startDate = startDate,
                  endDate = endDate))

      assertNotNull(observationId)

      val createdObservation = observationStore.fetchObservationById(observationId)

      assertEquals(
          ObservationState.Upcoming, createdObservation.state, "State should show as Upcoming")
      assertEquals(
          startDate, createdObservation.startDate, "Start date should match schedule input")
      assertEquals(endDate, createdObservation.endDate, "End date should match schedule input")

      eventPublisher.assertExactEventsPublished(
          setOf(ObservationScheduledEvent(createdObservation)))
    }

    private fun newObservationModel(
        plantingSiteId: PlantingSiteId,
        startDate: LocalDate = LocalDate.EPOCH,
        endDate: LocalDate = LocalDate.EPOCH.plusDays(1)
    ): NewObservationModel =
        NewObservationModel(
            plantingSiteId = plantingSiteId,
            id = null,
            startDate = startDate,
            endDate = endDate,
            state = ObservationState.Upcoming,
        )
  }

  @Nested
  inner class RescheduleObservation {
    private lateinit var observationId: ObservationId

    @BeforeEach
    fun setUp() {
      observationId = insertObservation()
    }

    @Test
    fun `throws access denied exception rescheduling an observation if no permission to reschedule observation`() {
      every { user.canRescheduleObservation(observationId) } returns false

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      assertThrows<AccessDeniedException> {
        service.rescheduleObservation(observationId, startDate, endDate)
      }
    }

    @Test
    fun `throws observation not found exception rescheduling an observation if no permission to reschedule or read observation`() {
      every { user.canRescheduleObservation(observationId) } returns false
      every { user.canReadObservation(observationId) } returns false

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      assertThrows<ObservationNotFoundException> {
        service.rescheduleObservation(observationId, startDate, endDate)
      }
    }

    @Test
    fun `throws exception rescheduling an observation if start date is in the past`() {
      every { clock.instant() } returns Instant.EPOCH.plus(1, ChronoUnit.DAYS)

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      assertThrows<InvalidObservationStartDateException> {
        service.rescheduleObservation(observationId, startDate, endDate)
      }
    }

    @Test
    fun `throws exception rescheduling an observation if start date is more than a year in the future`() {
      val startDate = LocalDate.EPOCH.plusYears(1).plusDays(1)
      val endDate = startDate.plusDays(1)

      assertThrows<InvalidObservationStartDateException> {
        service.rescheduleObservation(observationId, startDate, endDate)
      }
    }

    @Test
    fun `throws exception rescheduling an observation if end date is on or before the start date`() {
      val startDate = LocalDate.EPOCH.plusDays(5)
      val endDate = startDate.minusDays(2)

      assertThrows<InvalidObservationEndDateException> {
        service.rescheduleObservation(observationId, startDate, endDate)
      }
    }

    @Test
    fun `throws exception rescheduling an observation if end date is more than 2 months after the start date`() {
      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusMonths(2).plusDays(1)

      assertThrows<InvalidObservationEndDateException> {
        service.rescheduleObservation(observationId, startDate, endDate)
      }
    }

    @Test
    fun `throws exception rescheduling an observation if the observation is not currently in Overdue state`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      assertThrows<ObservationRescheduleStateException> {
        service.rescheduleObservation(observationId, startDate, endDate)
      }
    }

    @Test
    fun `reschedules an existing overdue observation`() {
      val observationId = insertObservation(state = ObservationState.Overdue)
      val originalObservation = observationStore.fetchObservationById(observationId)

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      service.rescheduleObservation(observationId, startDate, endDate)

      val updatedObservation = observationStore.fetchObservationById(observationId)
      assertEquals(
          ObservationState.Upcoming, updatedObservation.state, "State should show as Upcoming")
      assertEquals(startDate, updatedObservation.startDate, "Start date should be updated")
      assertEquals(endDate, updatedObservation.endDate, "End date should be updated")

      eventPublisher.assertExactEventsPublished(
          setOf(ObservationRescheduledEvent(originalObservation, updatedObservation)))
    }
  }

  @Nested
  inner class SitesToNotifySchedulingObservations {
    private val criteria = NotificationCriteria.ScheduleObservations

    @BeforeEach
    fun setUp() {
      every { user.canManageNotifications() } returns true
      every { terrawareServerConfig.observations } returns
          TerrawareServerConfig.ObservationsConfig(notifyOnFirstPlanting = true)
    }

    @Test
    fun `throws exception when no permission to manage notifications`() {
      every { user.canManageNotifications() } returns false

      assertThrows<AccessDeniedException> {
        service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria)
      }
    }

    @Test
    fun `returns empty results when there are no eligible sites to notify scheduling new observations`() {
      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns site with subzone plantings and no completed observations`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      val insertedPlantingSiteId = insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      assertEquals(
          listOf(insertedPlantingSiteId),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria))
    }

    @Test
    fun `returns empty results with observations completed more recent than two weeks`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results with observations completed earlier than two weeks but with other observations scheduled`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(state = ObservationState.Upcoming)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(2 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns sites with observations completed earlier than two weeks or have sub zone plantings`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      val plantingSiteIdWithCompletedObservation = insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(2 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      val anotherPlantingSiteIdWithCompletedObservation = insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(2 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      // planting site with a more recent completion
      insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      val plantingSiteIdWithPlantings = insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      assertEquals(
          setOf(
              plantingSiteIdWithCompletedObservation,
              anotherPlantingSiteIdWithCompletedObservation,
              plantingSiteIdWithPlantings),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).toSet())
    }
  }

  @Nested
  inner class SitesToNotifyRemindingSchedulingObservations {
    private val criteria = NotificationCriteria.RemindSchedulingObservations

    @BeforeEach
    fun setUp() {
      every { user.canManageNotifications() } returns true
      every { terrawareServerConfig.observations } returns
          TerrawareServerConfig.ObservationsConfig(notifyOnFirstPlanting = true)
    }

    @Test
    fun `returns empty results if planting site did not have notification sent to schedule observations`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting(PlantingsRow(createdTime = Instant.EPOCH.minus(7 * 4, ChronoUnit.DAYS)))

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns site with subzone plantings planted earlier than 4 weeks and no completed observations`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      val insertedPlantingSiteId =
          insertPlantingSite(
              PlantingSitesRow(scheduleObservationNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting(PlantingsRow(createdTime = Instant.EPOCH.minus(7 * 4, ChronoUnit.DAYS)))

      assertEquals(
          listOf(insertedPlantingSiteId),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria))
    }

    @Test
    fun `returns empty results with observations completed more recent than six weeks`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite(PlantingSitesRow(scheduleObservationNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results with observations completed earlier than six weeks but with other observations scheduled`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite(PlantingSitesRow(scheduleObservationNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(state = ObservationState.Upcoming)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns sites with observations completed earlier than six weeks or have sub zone plantings`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      val plantingSiteIdWithCompletedObservation =
          insertPlantingSite(
              PlantingSitesRow(scheduleObservationNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      val anotherPlantingSiteIdWithCompletedObservation =
          insertPlantingSite(
              PlantingSitesRow(scheduleObservationNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      // planting site with a more recent completion
      insertPlantingSite(PlantingSitesRow(scheduleObservationNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      val plantingSiteIdWithPlantings =
          insertPlantingSite(
              PlantingSitesRow(scheduleObservationNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting(PlantingsRow(createdTime = Instant.EPOCH.minus(4 * 7, ChronoUnit.DAYS)))

      assertEquals(
          setOf(
              plantingSiteIdWithCompletedObservation,
              anotherPlantingSiteIdWithCompletedObservation,
              plantingSiteIdWithPlantings),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).toSet())
    }
  }

  @Nested
  inner class SitesToNotifyObservationNotScheduledFirstNotification {
    private val criteria = NotificationCriteria.ObservationNotScheduledFirstNotification

    @BeforeEach
    fun setUp() {
      every { user.canManageNotifications() } returns true
      every { terrawareServerConfig.observations } returns
          TerrawareServerConfig.ObservationsConfig(notifyOnFirstPlanting = true)
    }

    @Test
    fun `returns site with subzone plantings planted earlier than 6 weeks and no completed observations`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      val insertedPlantingSiteId = insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting(PlantingsRow(createdTime = Instant.EPOCH.minus(7 * 6, ChronoUnit.DAYS)))

      assertEquals(
          listOf(insertedPlantingSiteId),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria))
    }

    @Test
    fun `returns empty results with observations completed more recent than eight weeks`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results with observations completed earlier than eight weeks but with other observations scheduled`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(state = ObservationState.Upcoming)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(8 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns sites with observations completed earlier than eight weeks or have sub zone plantings`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      val plantingSiteIdWithCompletedObservation = insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(8 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      val anotherPlantingSiteIdWithCompletedObservation = insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(8 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      // planting site with a more recent completion
      insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      val plantingSiteIdWithPlantings = insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting(PlantingsRow(createdTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)))

      assertEquals(
          setOf(
              plantingSiteIdWithCompletedObservation,
              anotherPlantingSiteIdWithCompletedObservation,
              plantingSiteIdWithPlantings),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).toSet())
    }
  }

  @Nested
  inner class SitesToNotifyObservationNotScheduledSecondNotification {
    private val criteria = NotificationCriteria.ObservationNotScheduledSecondNotification

    @BeforeEach
    fun setUp() {
      every { user.canManageNotifications() } returns true
      every { terrawareServerConfig.observations } returns
          TerrawareServerConfig.ObservationsConfig(notifyOnFirstPlanting = true)
    }

    @Test
    fun `returns empty results if planting site did not have notification sent to schedule observations`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting(PlantingsRow(createdTime = Instant.EPOCH.minus(7 * 14, ChronoUnit.DAYS)))

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns site with subzone plantings planted earlier than 14 weeks and no completed observations`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      val insertedPlantingSiteId =
          insertPlantingSite(
              PlantingSitesRow(observationNotScheduledFirstNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting(PlantingsRow(createdTime = Instant.EPOCH.minus(7 * 14, ChronoUnit.DAYS)))

      assertEquals(
          listOf(insertedPlantingSiteId),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria))
    }

    @Test
    fun `returns empty results with observations completed more recent than sixteen weeks`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite(
          PlantingSitesRow(observationNotScheduledFirstNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results with observations completed earlier than sixteen weeks but with other observations scheduled`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite(
          PlantingSitesRow(observationNotScheduledFirstNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(state = ObservationState.Upcoming)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns sites with observations completed earlier than sixteen weeks or have sub zone plantings`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      val plantingSiteIdWithCompletedObservation =
          insertPlantingSite(
              PlantingSitesRow(observationNotScheduledFirstNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      val anotherPlantingSiteIdWithCompletedObservation =
          insertPlantingSite(
              PlantingSitesRow(observationNotScheduledFirstNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      // planting site with a more recent completion
      insertPlantingSite(
          PlantingSitesRow(observationNotScheduledFirstNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      val plantingSiteIdWithPlantings =
          insertPlantingSite(
              PlantingSitesRow(observationNotScheduledFirstNotificationSentTime = Instant.EPOCH))
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting(PlantingsRow(createdTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)))

      assertEquals(
          setOf(
              plantingSiteIdWithCompletedObservation,
              anotherPlantingSiteIdWithCompletedObservation,
              plantingSiteIdWithPlantings),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).toSet())
    }
  }

  @Nested
  inner class ReplaceMonitoringPlot {
    private lateinit var observationId: ObservationId

    @BeforeEach
    fun setUp() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingZone(numPermanentClusters = 1, numTemporaryPlots = 1)
      insertPlantingSubzone(plantingCompletedTime = Instant.EPOCH)
      insertWithdrawal()
      insertDelivery()
      insertPlanting()
      observationId = insertObservation()

      every { user.canReplaceObservationPlot(any()) } returns true
      every { user.canUpdatePlantingSite(any()) } returns true
    }

    @Test
    fun `marks temporary plot as unavailable and destroys its permanent cluster if duration is long-term`() {
      val cluster1PlotId1 = insertMonitoringPlot(permanentCluster = 1, permanentClusterSubplot = 1)
      insertObservationPlot()
      val cluster1PlotId2 = insertMonitoringPlot(permanentCluster = 1, permanentClusterSubplot = 2)
      val cluster1PlotId3 = insertMonitoringPlot(permanentCluster = 1, permanentClusterSubplot = 3)
      val cluster1PlotId4 = insertMonitoringPlot(permanentCluster = 1, permanentClusterSubplot = 4)
      val cluster2PlotId1 = insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = 1)
      val cluster2PlotId2 = insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = 2)
      val cluster2PlotId3 = insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = 3)
      val cluster2PlotId4 = insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = 4)
      val cluster3PlotId1 = insertMonitoringPlot(permanentCluster = 3, permanentClusterSubplot = 1)
      val cluster3PlotId2 = insertMonitoringPlot(permanentCluster = 3, permanentClusterSubplot = 2)
      val cluster3PlotId3 = insertMonitoringPlot(permanentCluster = 3, permanentClusterSubplot = 3)
      val cluster3PlotId4 = insertMonitoringPlot(permanentCluster = 3, permanentClusterSubplot = 4)

      service.replaceMonitoringPlot(
          observationId, cluster1PlotId1, "Meteor strike", ReplacementDuration.LongTerm)

      assertFalse(
          monitoringPlotsDao.fetchOneById(cluster1PlotId1)!!.isAvailable!!, "Plot is available")

      val plots = monitoringPlotsDao.findAll().associateBy { it.id!! }
      assertEquals(
          mapOf(
              cluster1PlotId1 to null,
              cluster1PlotId2 to null,
              cluster1PlotId3 to null,
              cluster1PlotId4 to null,
              cluster2PlotId1 to 2,
              cluster2PlotId2 to 2,
              cluster2PlotId3 to 2,
              cluster2PlotId4 to 2,
              cluster3PlotId1 to 1,
              cluster3PlotId2 to 1,
              cluster3PlotId3 to 1,
              cluster3PlotId4 to 1,
          ),
          plots.mapValues { it.value.permanentCluster },
          "Should have removed permanent cluster number from initial cluster and moved highest-numbered cluster to first place")
    }

    @Test
    fun `does not mark temporary plot as unavailable if duration is temporary`() {
      val monitoringPlotId = insertMonitoringPlot()
      insertObservationPlot()

      service.replaceMonitoringPlot(
          observationId, monitoringPlotId, "Mudslide", ReplacementDuration.Temporary)

      assertTrue(
          monitoringPlotsDao.fetchOneById(monitoringPlotId)!!.isAvailable!!, "Plot is available")
    }

    @Test
    fun `replaces temporary plot with another one from the same subzone`() {
      val monitoringPlotId = insertMonitoringPlot(fullName = "full name")
      insertObservationPlot()
      val otherPlotId = insertMonitoringPlot(fullName = "other full name")

      val result =
          service.replaceMonitoringPlot(
              observationId, monitoringPlotId, "Mudslide", ReplacementDuration.Temporary)

      assertEquals(
          ReplacementResult(
              listOf(ReplacementResultPlot(otherPlotId, "other full name")),
              listOf(ReplacementResultPlot(monitoringPlotId, "full name"))),
          result)

      assertEquals(
          listOf(otherPlotId),
          observationPlotsDao.findAll().map { it.monitoringPlotId },
          "Observation should only have replacement plot")
    }

    @Test
    fun `replaces entire permanent cluster if this is the first observation and there are no completed plots`() {
      val cluster1PlotId1 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 1, fullName = "cluster1PlotId1")
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId2 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 2, fullName = "cluster1PlotId2")
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId3 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 3, fullName = "cluster1PlotId3")
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId4 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 4, fullName = "cluster1PlotId4")
      insertObservationPlot(isPermanent = true)
      val cluster2PlotId1 =
          insertMonitoringPlot(
              permanentCluster = 2, permanentClusterSubplot = 1, fullName = "cluster2PlotId1")
      val cluster2PlotId2 =
          insertMonitoringPlot(
              permanentCluster = 2, permanentClusterSubplot = 2, fullName = "cluster2PlotId2")
      val cluster2PlotId3 =
          insertMonitoringPlot(
              permanentCluster = 2, permanentClusterSubplot = 3, fullName = "cluster2PlotId3")
      val cluster2PlotId4 =
          insertMonitoringPlot(
              permanentCluster = 2, permanentClusterSubplot = 4, fullName = "cluster2PlotId4")

      val result =
          service.replaceMonitoringPlot(
              observationId, cluster1PlotId1, "why not", ReplacementDuration.Temporary)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlots =
                  listOf(
                      ReplacementResultPlot(cluster2PlotId1, "cluster2PlotId1"),
                      ReplacementResultPlot(cluster2PlotId2, "cluster2PlotId2"),
                      ReplacementResultPlot(cluster2PlotId3, "cluster2PlotId3"),
                      ReplacementResultPlot(cluster2PlotId4, "cluster2PlotId4")),
              removedMonitoringPlots =
                  listOf(
                      ReplacementResultPlot(cluster1PlotId1, "cluster1PlotId1"),
                      ReplacementResultPlot(cluster1PlotId2, "cluster1PlotId2"),
                      ReplacementResultPlot(cluster1PlotId3, "cluster1PlotId3"),
                      ReplacementResultPlot(cluster1PlotId4, "cluster1PlotId4"))),
          result)

      assertEquals(
          listOf(1, 1, 1, 1),
          monitoringPlotsDao
              .fetchById(cluster2PlotId1, cluster2PlotId2, cluster2PlotId3, cluster2PlotId4)
              .map { it.permanentCluster },
          "Should have moved second permanent cluster to first place")
      assertEquals(
          listOf(2, 2, 2, 2),
          monitoringPlotsDao
              .fetchById(cluster1PlotId1, cluster1PlotId2, cluster1PlotId3, cluster1PlotId4)
              .map { it.permanentCluster },
          "Should have moved first permanent cluster to second place")
    }

    @Test
    fun `removes permanent cluster if replacement cluster is in an unplanted subzone`() {
      val cluster1PlotId1 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 1, fullName = "cluster1PlotId1")
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId2 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 2, fullName = "cluster1PlotId2")
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId3 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 3, fullName = "cluster1PlotId3")
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId4 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 4, fullName = "cluster1PlotId4")
      insertObservationPlot(isPermanent = true)
      insertPlantingSubzone()
      val cluster2PlotId1 = insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = 1)
      val cluster2PlotId2 = insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = 2)
      val cluster2PlotId3 = insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = 3)
      val cluster2PlotId4 = insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = 4)

      val result =
          service.replaceMonitoringPlot(
              observationId, cluster1PlotId1, "why not", ReplacementDuration.Temporary)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlots = emptyList(),
              removedMonitoringPlots =
                  listOf(
                      ReplacementResultPlot(cluster1PlotId1, "cluster1PlotId1"),
                      ReplacementResultPlot(cluster1PlotId2, "cluster1PlotId2"),
                      ReplacementResultPlot(cluster1PlotId3, "cluster1PlotId3"),
                      ReplacementResultPlot(cluster1PlotId4, "cluster1PlotId4"))),
          result)

      assertEquals(
          listOf(1, 1, 1, 1),
          monitoringPlotsDao
              .fetchById(cluster2PlotId1, cluster2PlotId2, cluster2PlotId3, cluster2PlotId4)
              .map { it.permanentCluster },
          "Should have moved second permanent cluster to first place")
      assertEquals(
          listOf(2, 2, 2, 2),
          monitoringPlotsDao
              .fetchById(cluster1PlotId1, cluster1PlotId2, cluster1PlotId3, cluster1PlotId4)
              .map { it.permanentCluster },
          "Should have moved first permanent cluster to second place")
    }

    @Test
    fun `marks permanent plot as unavailable and destroys its cluster if this is the first observation and duration is long-term`() {
      val cluster1PlotId1 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 1, fullName = "cluster1PlotId1")
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId2 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 2, fullName = "cluster1PlotId2")
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId3 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 3, fullName = "cluster1PlotId3")
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId4 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 4, fullName = "cluster1PlotId4")
      insertObservationPlot(isPermanent = true)
      val cluster2PlotId1 = insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = 1)
      val cluster2PlotId2 = insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = 2)
      val cluster2PlotId3 = insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = 3)
      val cluster2PlotId4 = insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = 4)
      val cluster3PlotId1 =
          insertMonitoringPlot(
              permanentCluster = 3, permanentClusterSubplot = 1, fullName = "cluster3PlotId1")
      val cluster3PlotId2 =
          insertMonitoringPlot(
              permanentCluster = 3, permanentClusterSubplot = 2, fullName = "cluster3PlotId2")
      val cluster3PlotId3 =
          insertMonitoringPlot(
              permanentCluster = 3, permanentClusterSubplot = 3, fullName = "cluster3PlotId3")
      val cluster3PlotId4 =
          insertMonitoringPlot(
              permanentCluster = 3, permanentClusterSubplot = 4, fullName = "cluster3PlotId4")

      val result =
          service.replaceMonitoringPlot(
              observationId, cluster1PlotId1, "why not", ReplacementDuration.LongTerm)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlots =
                  listOf(
                      ReplacementResultPlot(cluster3PlotId1, "cluster3PlotId1"),
                      ReplacementResultPlot(cluster3PlotId2, "cluster3PlotId2"),
                      ReplacementResultPlot(cluster3PlotId3, "cluster3PlotId3"),
                      ReplacementResultPlot(cluster3PlotId4, "cluster3PlotId4")),
              removedMonitoringPlots =
                  listOf(
                      ReplacementResultPlot(cluster1PlotId1, "cluster1PlotId1"),
                      ReplacementResultPlot(cluster1PlotId2, "cluster1PlotId2"),
                      ReplacementResultPlot(cluster1PlotId3, "cluster1PlotId3"),
                      ReplacementResultPlot(cluster1PlotId4, "cluster1PlotId4"))),
          result)

      val plots = monitoringPlotsDao.findAll().associateBy { it.id!! }
      assertEquals(
          mapOf(
              cluster1PlotId1 to null,
              cluster1PlotId2 to null,
              cluster1PlotId3 to null,
              cluster1PlotId4 to null,
              cluster2PlotId1 to 2,
              cluster2PlotId2 to 2,
              cluster2PlotId3 to 2,
              cluster2PlotId4 to 2,
              cluster3PlotId1 to 1,
              cluster3PlotId2 to 1,
              cluster3PlotId3 to 1,
              cluster3PlotId4 to 1,
          ),
          plots.mapValues { it.value.permanentCluster },
          "Should have removed permanent cluster number from initial cluster and moved highest-numbered cluster to first place")
      assertEquals(
          mapOf(
              cluster1PlotId1 to false,
              cluster1PlotId2 to true,
              cluster1PlotId3 to true,
              cluster1PlotId4 to true,
              cluster2PlotId1 to true,
              cluster2PlotId2 to true,
              cluster2PlotId3 to true,
              cluster2PlotId4 to true,
              cluster3PlotId1 to true,
              cluster3PlotId2 to true,
              cluster3PlotId3 to true,
              cluster3PlotId4 to true,
          ),
          plots.mapValues { it.value.isAvailable },
          "Should have marked removed plot as unavailable but kept others as available")
    }

    @Test
    fun `removes permanent plot but keeps it available if this is the first observation and there are already completed plots`() {
      val cluster1PlotId1 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 1, fullName = "full name")
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId2 = insertMonitoringPlot(permanentCluster = 1, permanentClusterSubplot = 2)
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId3 = insertMonitoringPlot(permanentCluster = 1, permanentClusterSubplot = 3)
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId4 = insertMonitoringPlot(permanentCluster = 1, permanentClusterSubplot = 4)
      insertObservationPlot(
          isPermanent = true,
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          completedBy = user.userId,
          completedTime = Instant.EPOCH)

      val result =
          service.replaceMonitoringPlot(
              observationId, cluster1PlotId1, "forest fire", ReplacementDuration.LongTerm)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlots = emptyList(),
              removedMonitoringPlots = listOf(ReplacementResultPlot(cluster1PlotId1, "full name"))),
          result)

      assertEquals(
          true,
          monitoringPlotsDao.fetchOneById(cluster1PlotId1)!!.isAvailable,
          "Monitoring plot should remain available")
      assertEquals(
          setOf(cluster1PlotId2, cluster1PlotId3, cluster1PlotId4),
          observationPlotsDao
              .fetchByObservationId(observationId)
              .map { it.monitoringPlotId }
              .toSet(),
          "Other plots in cluster should remain in observation")
    }

    @Test
    fun `removes permanent plot but keeps it available if this is not the first observation`() {
      observationsDao.update(
          observationsDao
              .fetchOneById(inserted.observationId)!!
              .copy(completedTime = Instant.EPOCH, stateId = ObservationState.Completed))

      val newObservationId = insertObservation()

      val cluster1PlotId1 =
          insertMonitoringPlot(
              permanentCluster = 1, permanentClusterSubplot = 1, fullName = "full name")
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId2 = insertMonitoringPlot(permanentCluster = 1, permanentClusterSubplot = 2)
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId3 = insertMonitoringPlot(permanentCluster = 1, permanentClusterSubplot = 3)
      insertObservationPlot(isPermanent = true)
      val cluster1PlotId4 = insertMonitoringPlot(permanentCluster = 1, permanentClusterSubplot = 4)
      insertObservationPlot(isPermanent = true)

      val result =
          service.replaceMonitoringPlot(
              newObservationId, cluster1PlotId1, "forest fire", ReplacementDuration.LongTerm)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlots = emptyList(),
              removedMonitoringPlots = listOf(ReplacementResultPlot(cluster1PlotId1, "full name"))),
          result)

      assertEquals(
          true,
          monitoringPlotsDao.fetchOneById(cluster1PlotId1)!!.isAvailable,
          "Monitoring plot should remain available")
      assertEquals(
          setOf(cluster1PlotId2, cluster1PlotId3, cluster1PlotId4),
          observationPlotsDao
              .fetchByObservationId(newObservationId)
              .map { it.monitoringPlotId }
              .toSet(),
          "Other plots in cluster should remain in observation")
    }

    @Test
    fun `publishes event`() {
      val monitoringPlotId = insertMonitoringPlot()
      insertObservationPlot()

      service.replaceMonitoringPlot(
          observationId, monitoringPlotId, "justification", ReplacementDuration.Temporary)

      // Default values from insertObservation()
      val observation =
          ExistingObservationModel(
              endDate = LocalDate.of(2023, 1, 31),
              id = observationId,
              plantingSiteId = inserted.plantingSiteId,
              startDate = LocalDate.of(2023, 1, 1),
              state = ObservationState.InProgress)

      eventPublisher.assertEventPublished(
          ObservationPlotReplacedEvent(
              duration = ReplacementDuration.Temporary,
              justification = "justification",
              observation = observation,
              monitoringPlotId = monitoringPlotId))
    }

    @Test
    fun `throws exception if plot is already completed`() {
      val monitoringPlotId = insertMonitoringPlot()
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          completedBy = user.userId,
          completedTime = Instant.EPOCH)

      assertThrows<PlotAlreadyCompletedException> {
        service.replaceMonitoringPlot(
            observationId, monitoringPlotId, "justification", ReplacementDuration.LongTerm)
      }
    }

    @Test
    fun `throws exception if monitoring plot not in observation`() {
      val otherPlotId = insertMonitoringPlot()

      assertThrows<PlotNotInObservationException> {
        service.replaceMonitoringPlot(
            observationId, otherPlotId, "justification", ReplacementDuration.LongTerm)
      }
    }

    @Test
    fun `throws access denied exception if no permission to replace plot`() {
      val monitoringPlotId = insertMonitoringPlot()
      insertObservationPlot()

      every { user.canReplaceObservationPlot(observationId) } returns false

      assertThrows<AccessDeniedException> {
        service.replaceMonitoringPlot(
            observationId, monitoringPlotId, "justification", ReplacementDuration.LongTerm)
      }
    }
  }
}
