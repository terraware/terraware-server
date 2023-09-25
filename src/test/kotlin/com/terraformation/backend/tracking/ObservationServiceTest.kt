package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
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
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.db.ScheduleObservationWithoutPlantsException
import com.terraformation.backend.tracking.event.ObservationStartedEvent
import com.terraformation.backend.tracking.model.NewObservationModel
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
import org.junit.jupiter.api.Assertions.assertNotNull
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
        parentStore)
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
    fun `throws exception scheduling an observation if no permission to create observation`() {
      every { user.canCreateObservation(plantingSiteId) } returns false

      assertThrows<AccessDeniedException> {
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

      val createdObservation = observationsDao.fetchOneById(observationId)

      assertEquals(
          ObservationState.Upcoming, createdObservation!!.stateId, "State should show as Upcoming")
      assertEquals(
          startDate, createdObservation.startDate, "Start date should match schedule input")
      assertEquals(endDate, createdObservation.endDate, "End date should match schedule input")
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
    fun `throws exception rescheduling an observation if no permission to update observation`() {
      every { user.canUpdateObservation(observationId) } returns false

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      assertThrows<AccessDeniedException> {
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

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      service.rescheduleObservation(observationId, startDate, endDate)

      val updatedObservation = observationsDao.fetchOneById(observationId)
      assertEquals(
          ObservationState.Upcoming, updatedObservation!!.stateId, "State should show as Upcoming")
      assertEquals(startDate, updatedObservation.startDate, "Start date should be updated")
      assertEquals(endDate, updatedObservation.endDate, "End date should be updated")
    }
  }
}
