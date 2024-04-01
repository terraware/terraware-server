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
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPhotosRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
import com.terraformation.backend.onePixelPng
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
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NotificationCriteria
import com.terraformation.backend.tracking.model.ReplacementDuration
import com.terraformation.backend.tracking.model.ReplacementResult
import com.terraformation.backend.util.Turtle
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

class ObservationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = spyk(TestClock())
  private val eventPublisher = TestEventPublisher()
  private val fileStore = InMemoryFileStore()
  private val thumbnailStore: ThumbnailStore = mockk()
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val fileService: FileService by lazy {
    FileService(
        dslContext,
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        mockk(),
        filesDao,
        fileStore,
        thumbnailStore)
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
        plantingSeasonsDao,
        plantingSitesDao,
        plantingSubzonesDao,
        plantingZonesDao)
  }

  private val service: ObservationService by lazy {
    ObservationService(
        clock,
        dslContext,
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
    plantingSiteId = insertPlantingSite(x = 0, width = 11, gridOrigin = point(0))

    every { user.canCreateObservation(any()) } returns true
    every { user.canManageObservation(any()) } returns true
    every { user.canReadMonitoringPlot(any()) } returns true
    every { user.canReadObservation(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canReadPlantingZone(any()) } returns true
    every { user.canRescheduleObservation(any()) } returns true
    every { user.canScheduleObservation(any()) } returns true
    every { user.canUpdateObservation(any()) } returns true
    every { user.canUpdatePlantingSite(any()) } returns true
  }

  @Nested
  inner class StartObservation {
    @Test
    fun `assigns correct plots to planting zones`() {
      // Given a planting site with this structure:
      //
      //              Zone 1                Zone 2
      //     +-------------------------|-------------+
      //     |           |             |             |
      //     | Subzone 1 | Subzone 2   | Subzone 3   |
      //     | (planted) | (no plants) | (no plants) |
      //     |           |             |             |
      //     +-------------------------|-------------+
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
      // - One permanent cluster with four plots that all lie in subzone 1.
      // - One temporary plot in subzone 1. The zone is configured for 3 temporary plots. 2 of them
      //   are spread evenly across the 2 subzones, and the remaining one is placed in the subzone
      //   with the fewest permanent plots, which is subzone 2, but subzone 2's plots are excluded
      //   because it has no plants.
      // - Nothing from zone 2 because it has no plants.

      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(
          x = 0, width = 8, height = 2, numPermanentClusters = 2, numTemporaryPlots = 3)
      val subzone1Boundary =
          Turtle(point(0)).makeMultiPolygon {
            rectangle(5 * MONITORING_PLOT_SIZE, 2 * MONITORING_PLOT_SIZE)
          }
      insertPlantingSubzone(boundary = subzone1Boundary)
      insertPlanting()
      insertCluster(1)
      insertCluster(3, x = 2, y = 0)
      insertMonitoringPlot(x = 4, y = 0)
      insertMonitoringPlot(x = 4, y = 1)

      insertPlantingSubzone(x = 5, width = 3, height = 2)
      insertCluster(2, x = 5, y = 0)
      insertMonitoringPlot(x = 7, y = 0)
      insertMonitoringPlot(x = 7, y = 1)

      insertPlantingZone(
          x = 8, width = 3, height = 2, numPermanentClusters = 2, numTemporaryPlots = 2)
      insertPlantingSubzone(x = 8, width = 3, height = 2)
      insertCluster(1, x = 8, y = 0)

      val observationId = insertObservation(state = ObservationState.Upcoming)

      service.startObservation(observationId)

      val observationPlots = observationPlotsDao.findAll()
      val monitoringPlots = monitoringPlotsDao.findAll().associateBy { it.id }

      assertEquals(4, observationPlots.count { it.isPermanent!! }, "Number of permanent plots")
      assertEquals(1, observationPlots.count { !it.isPermanent!! }, "Number of temporary plots")

      observationPlots.forEach { observationPlot ->
        val plotBoundary = monitoringPlots[observationPlot.monitoringPlotId]!!.boundary!!
        if (plotBoundary.intersection(subzone1Boundary).area < plotBoundary.area * 0.99999) {
          fail(
              "Plot boundary $plotBoundary does not fall within subzone boundary $subzone1Boundary")
        }
      }

      assertEquals(
          ObservationState.InProgress,
          observationsDao.fetchOneById(observationId)!!.stateId,
          "Observation state")

      eventPublisher.assertExactEventsPublished(
          setOf(ObservationStartedEvent(observationStore.fetchObservationById(observationId))))
    }

    @Test
    fun `creates new plots in correct zones`() {
      // Given a planting site with this structure:
      //
      //              Zone 1                Zone 2
      //     +-------------------------|-------------+
      //     |           |             |             |
      //     | Subzone 1 | Subzone 2   | Subzone 3   |
      //     | (planted) | (no plants) | (no plants) |
      //     |           |             |             |
      //     +-------------------------|-------------+
      //
      // Zone 1 (1 permanent, 3 temporary)
      //   Subzone 1 (has plants)
      //   Subzone 2 (no plants)
      // Zone 2 (2 permanent, 2 temporary)
      //   Subzone 3 (no plants)
      //
      // In zone 1, the service should create one permanent cluster with four plots. The plots might
      // lie completely in subzone 1, completely in subzone 2, or half in each subzone, depending on
      // the random number generator, and the cluster may or may not be included in the observation.
      //
      // The placement of the cluster will also determine how many temporary plots are created and
      // included in the observation.
      //
      // If the cluster is all in subzone 1:
      // - It should be included in the observation.
      // - We should get one temporary plot in subzone 1. The zone is configured for 3 temporary
      //   plots. 2 of them are spread evenly across the 2 subzones, and the remaining one is placed
      //   in the subzone with the fewest permanent plots, which is subzone 2, but subzone 2's plots
      //   are excluded because it has no plants.
      // If the cluster is partially in subzone 1 and partially in subzone 2:
      // - It should not be included in the observation because we only include permanent clusters
      //   whose plots all lie in planted subzones.
      // - We should get two temporary plots in subzone 1. Two of zone 1's temporary plots are
      //   spread evenly across the two subzones, and the remaining plot is placed in the subzone
      //   with the fewest permanent plots, but in this case the subzones have the same number.
      //   As a tiebreaker, planted subzones are preferred over unplanted ones, which means we
      //   should choose subzone 1.
      // If the cluster is all in subzone 2:
      // - We should get two temporary plots in subzone 1. Two of zone 1's temporary plots are
      //   spread evenly across the two subzones, and the remaining plot is placed in the subzone
      //   with the fewest temporary plots, which is subzone 1 in this case.
      //
      // In zone 2, the service should create two permanent clusters, but neither of them should
      // be included in the observation since they all lie in an unplanted subzone.

      plantingSiteId = insertPlantingSite(x = 0, width = 14, gridOrigin = point(0))
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(
          x = 0, width = 6, height = 2, numPermanentClusters = 1, numTemporaryPlots = 3)
      val subzone1Boundary =
          Turtle(point(0)).makeMultiPolygon {
            rectangle(3 * MONITORING_PLOT_SIZE, 2 * MONITORING_PLOT_SIZE)
          }
      val subzone1Id = insertPlantingSubzone(boundary = subzone1Boundary)
      insertPlanting()

      val subzone2Id = insertPlantingSubzone(x = 3, width = 3, height = 2)

      insertPlantingZone(
          x = 6, width = 8, height = 2, numPermanentClusters = 2, numTemporaryPlots = 2)
      insertPlantingSubzone(x = 6, width = 8, height = 2)

      val observationId = insertObservation(state = ObservationState.Upcoming)

      // Make sure we actually get all the possible plot configurations.
      var got0PermanentPlotsInSubzone1 = false
      var got2PermanentPlotsInSubzone1 = false
      var got4PermanentPlotsInSubzone1 = false
      val maxTestRuns = 100
      var testRuns = 0

      while (testRuns++ < maxTestRuns &&
          !(got0PermanentPlotsInSubzone1 &&
              got2PermanentPlotsInSubzone1 &&
              got4PermanentPlotsInSubzone1)) {
        dslContext.savepoint("start").execute()

        service.startObservation(observationId)

        val observationPlots = observationPlotsDao.findAll()
        val monitoringPlots = monitoringPlotsDao.findAll()
        val monitoringPlotsById = monitoringPlots.associateBy { it.id }

        // All selected plots should be in subzone 1; make sure their boundaries agree with their
        // subzone numbers.
        observationPlots.forEach { observationPlot ->
          val plot = monitoringPlotsById[observationPlot.monitoringPlotId]!!
          val plotBoundary = plot.boundary!!

          assertEquals(
              subzone1Id, plot.plantingSubzoneId, "Planting subzone ID for plot ${plot.id}")

          if (plotBoundary.intersection(subzone1Boundary).area < plotBoundary.area * 0.99999) {
            fail(
                "Plot boundary $plotBoundary does not fall within subzone boundary $subzone1Boundary")
          }
        }

        val numPermanentPlotsBySubzone: Map<PlantingSubzoneId, Int> =
            monitoringPlots
                .filter { it.permanentCluster != null }
                .groupBy { it.plantingSubzoneId!! }
                .mapValues { it.value.size }
        val numPermanentPlotsInSubzone1 = numPermanentPlotsBySubzone[subzone1Id] ?: 0
        val numPermanentPlotsInSubzone2 = numPermanentPlotsBySubzone[subzone2Id] ?: 0

        assertEquals(
            12,
            monitoringPlots.count { it.permanentCluster != null },
            "Total number of permanent plots created")
        assertEquals(
            4,
            numPermanentPlotsInSubzone1 + numPermanentPlotsInSubzone2,
            "Number of permanent plots created in zone 1")

        val expectedTemporaryPlots =
            when (numPermanentPlotsInSubzone1) {
              4 -> 1
              2 -> 2
              0 -> 2
              else ->
                  fail(
                      "Expected 0, 2, or 4 permanent plots in subzone $subzone1Id, but " +
                          "got $numPermanentPlotsInSubzone1")
            }

        assertEquals(
            expectedTemporaryPlots,
            observationPlots.count { !it.isPermanent!! },
            "Number of temporary plots in observation")

        assertEquals(
            ObservationState.InProgress,
            observationsDao.fetchOneById(observationId)!!.stateId,
            "Observation state")

        eventPublisher.assertExactEventsPublished(
            setOf(ObservationStartedEvent(observationStore.fetchObservationById(observationId))))

        when (numPermanentPlotsInSubzone1) {
          0 -> got0PermanentPlotsInSubzone1 = true
          2 -> got2PermanentPlotsInSubzone1 = true
          4 -> got4PermanentPlotsInSubzone1 = true
        }

        eventPublisher.clear()
        dslContext.rollback().toSavepoint("start").execute()
      }

      assertNotEquals(
          maxTestRuns,
          testRuns,
          "Number of test runs without seeing all possible permanent plot counts")
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

    private val metadata = FileMetadata.of(MediaType.IMAGE_JPEG_VALUE, "filename", 1L)

    @BeforeEach
    fun setUp() {
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
                point(1),
                ObservationPlotPosition.NortheastCorner,
                content.inputStream(),
                metadata)
      }

      @Test
      fun `returns photo data`() {
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
                point(1),
                ObservationPlotPosition.NortheastCorner,
                byteArrayOf(1).inputStream(),
                metadata)

        fileStore.assertFileExists(filesDao.fetchOneById(fileId)!!.storageUrl!!)

        assertEquals(
            listOf(
                ObservationPhotosRow(
                    fileId,
                    observationId,
                    plotId,
                    ObservationPlotPosition.NortheastCorner,
                    point(1))),
            observationPhotosDao.findAll())
      }

      @Test
      fun `throws exception if no permission to update observation`() {
        every { user.canUpdateObservation(observationId) } returns false

        assertThrows<AccessDeniedException> {
          service.storePhoto(
              observationId,
              plotId,
              point(1),
              ObservationPlotPosition.NortheastCorner,
              byteArrayOf(1).inputStream(),
              metadata)
        }
      }
    }

    @Nested
    inner class OnPlantingSiteDeletion {
      @Test
      fun `only deletes photos for planting site that is being deleted`() {
        every { thumbnailStore.deleteThumbnails(any()) } just Runs

        service.storePhoto(
            observationId,
            plotId,
            point(1),
            ObservationPlotPosition.NortheastCorner,
            onePixelPng.inputStream(),
            metadata)

        insertPlantingSite()
        insertPlantingZone()
        insertPlantingSubzone()
        insertMonitoringPlot()
        insertObservation()
        insertObservationPlot()

        val otherSiteFileId =
            service.storePhoto(
                inserted.observationId,
                inserted.monitoringPlotId,
                point(1),
                ObservationPlotPosition.SouthwestCorner,
                onePixelPng.inputStream(),
                metadata)

        service.on(PlantingSiteDeletionStartedEvent(plantingSiteId))

        assertEquals(
            listOf(otherSiteFileId),
            filesDao.findAll().map { it.id },
            "Files table should only have file from other observation")
        assertEquals(
            listOf(otherSiteFileId),
            observationPhotosDao.findAll().map { it.fileId },
            "Observation photos table should only have file from other observation")
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
    fun `throws exception rescheduling an observation if the observation is Completed`() {
      val observationId =
          insertObservation(state = ObservationState.Completed, completedTime = Instant.EPOCH)

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      assertThrows<ObservationRescheduleStateException> {
        service.rescheduleObservation(observationId, startDate, endDate)
      }
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "InProgress",
                "Overdue",
            ])
    fun `throws exception rescheduling an In-Progress or Overdue observation if there are observed plots`(
        stateName: String
    ) {
      insertPlantingSite()
      insertPlantingZone(numPermanentClusters = 1, numTemporaryPlots = 1)
      insertPlantingSubzone()
      insertMonitoringPlot()

      val observationId = insertObservation(state = ObservationState.valueOf(stateName))
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      assertThrows<ObservationRescheduleStateException> {
        service.rescheduleObservation(observationId, startDate, endDate)
      }
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "InProgress",
                "Overdue",
            ])
    fun `reschedules an In-Progress or Overdue observation if there are no observed plots`(
        stateName: String
    ) {
      insertPlantingSite()
      insertPlantingZone(numPermanentClusters = 1, numTemporaryPlots = 1)
      insertPlantingSubzone()
      insertMonitoringPlot()

      val observationId = insertObservation(state = ObservationState.valueOf(stateName))
      insertObservationPlot()
      val originalObservation = observationStore.fetchObservationById(observationId)

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      service.rescheduleObservation(observationId, startDate, endDate)

      val updatedObservation = observationStore.fetchObservationById(observationId)
      assertEquals(
          ObservationState.Upcoming, updatedObservation.state, "State should show as Upcoming")
      assertEquals(startDate, updatedObservation.startDate, "Start date should be updated")
      assertEquals(endDate, updatedObservation.endDate, "End date should be updated")
      assertEquals(
          emptyList<Any>(), observationPlotsDao.findAll(), "Observation plots should be removed")

      eventPublisher.assertExactEventsPublished(
          setOf(ObservationRescheduledEvent(originalObservation, updatedObservation)))
    }

    @Test
    fun `reschedules an upcoming observation`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)
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
      val insertedPlantingSiteId = insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)
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
      insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)
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
      insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)
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

      val plantingSiteIdWithCompletedObservation = insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      val anotherPlantingSiteIdWithCompletedObservation = insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      // planting site with a more recent completion
      insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      val plantingSiteIdWithPlantings = insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)
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
    fun `returns empty results if notification already sent`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(8 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      assertEquals(
          emptyList<Any>(), service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria))
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
      val insertedPlantingSiteId = insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)
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
      insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)
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
      insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)
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
    fun `returns empty results if notification already sent`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      insertPlantingSite()
      insertPlantingSiteNotification(
          type = NotificationType.ObservationNotScheduledSupport, number = 2)
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      assertEquals(
          emptyList<Any>(), service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria))
    }

    @Test
    fun `returns sites with observations completed earlier than sixteen weeks or have sub zone plantings`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      val plantingSiteIdWithCompletedObservation = insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      val anotherPlantingSiteIdWithCompletedObservation = insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      // planting site with a more recent completion
      insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      val plantingSiteIdWithPlantings = insertPlantingSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)
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
      insertPlantingZone(numPermanentClusters = 1, numTemporaryPlots = 1, width = 2, height = 7)
      insertPlantingSubzone(plantingCompletedTime = Instant.EPOCH, width = 2, height = 7)
      insertWithdrawal()
      insertDelivery()
      insertPlanting()
      observationId = insertObservation()

      every { user.canReplaceObservationPlot(any()) } returns true
      every { user.canUpdatePlantingSite(any()) } returns true
    }

    @Test
    fun `marks temporary plot as unavailable and destroys its permanent cluster if duration is long-term`() {
      val cluster1 = insertCluster(1)
      insertCluster(2)
      insertCluster(3)
      insertObservationPlot(monitoringPlotId = cluster1[0])

      service.replaceMonitoringPlot(
          observationId, cluster1[0], "Meteor strike", ReplacementDuration.LongTerm)

      val plots = monitoringPlotsDao.findAll().associateBy { it.id!! }

      assertEquals(
          listOf<MonitoringPlotId?>(null, null, null, null),
          cluster1.map { plots[it]!!.permanentCluster },
          "Permanent cluster numbers of cluster whose plot was replaced")

      assertFalse(plots[cluster1[0]]!!.isAvailable!!, "Replaced plot is available")

      plots.values
          .filter { it.id != cluster1[0] }
          .forEach { plot -> assertTrue(plot.isAvailable!!, "Plot ${plot.id} is available") }
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
    fun `can use previously-created plot as replacement`() {
      insertPlantingSubzone(width = 2, height = 1, plantingCompletedTime = Instant.EPOCH)
      val monitoringPlotId = insertMonitoringPlot(x = 0)
      insertObservationPlot()
      val otherPlotId = insertMonitoringPlot(x = 1)

      val result =
          service.replaceMonitoringPlot(
              observationId, monitoringPlotId, "Mudslide", ReplacementDuration.Temporary)

      assertEquals(ReplacementResult(setOf(otherPlotId), setOf(monitoringPlotId)), result)

      assertEquals(
          listOf(otherPlotId),
          observationPlotsDao.findAll().map { it.monitoringPlotId },
          "Observation should only have replacement plot")
    }

    @Test
    fun `does not return an unavailable plot even if there are no other options`() {
      insertPlantingSubzone(width = 2, height = 1, plantingCompletedTime = Instant.EPOCH)
      val monitoringPlotId = insertMonitoringPlot(x = 0)
      insertObservationPlot()
      insertMonitoringPlot(x = 1, isAvailable = false)

      val result =
          service.replaceMonitoringPlot(
              observationId, monitoringPlotId, "Mudslide", ReplacementDuration.Temporary)

      assertEquals(ReplacementResult(emptySet(), setOf(monitoringPlotId)), result)

      assertEquals(
          emptyList<Any>(), observationPlotsDao.findAll(), "Observation should not have any plots")
    }

    @Test
    fun `replaces entire permanent cluster if this is the first observation and there are no completed plots`() {
      insertPlantingZone(numPermanentClusters = 1, width = 2, height = 4)
      insertPlantingSubzone(width = 2, height = 4, plantingCompletedTime = Instant.EPOCH)
      val cluster1 = insertCluster(1, isPermanent = true)
      val cluster2 = insertCluster(2)

      val result =
          service.replaceMonitoringPlot(
              observationId, cluster1[0], "why not", ReplacementDuration.Temporary)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = cluster2.toSet(),
              removedMonitoringPlotIds = cluster1.toSet()),
          result)

      assertEquals(
          listOf(1, 1, 1, 1),
          monitoringPlotsDao.fetchById(*cluster2.toTypedArray()).map { it.permanentCluster },
          "Should have moved second permanent cluster to first place")
      monitoringPlotsDao.fetchById(*cluster1.toTypedArray()).forEach { plot ->
        assertNotEquals(1, plot.permanentCluster, "Plot ${plot.id} cluster")
      }
    }

    @Test
    fun `removes permanent cluster if replacement cluster is in an unplanted subzone`() {
      insertPlantingZone(numPermanentClusters = 1, width = 2, height = 4)
      insertPlantingSubzone(width = 2, height = 2, plantingCompletedTime = Instant.EPOCH)
      val cluster1 = insertCluster(1, isPermanent = true)
      insertPlantingSubzone(y = 2, width = 2, height = 2)
      val cluster2 = insertCluster(2)

      val result =
          service.replaceMonitoringPlot(
              observationId, cluster1[0], "why not", ReplacementDuration.Temporary)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = emptySet(), removedMonitoringPlotIds = cluster1.toSet()),
          result)

      assertEquals(
          listOf(1, 1, 1, 1),
          monitoringPlotsDao.fetchById(*cluster2.toTypedArray()).map { it.permanentCluster },
          "Should have moved second permanent cluster to first place")
      monitoringPlotsDao.fetchById(*cluster1.toTypedArray()).forEach { plot ->
        assertNotEquals(1, plot.permanentCluster, "Plot ${plot.id} cluster")
      }
    }

    @Test
    fun `marks permanent plot as unavailable and swaps in a new cluster if this is the first observation and duration is long-term`() {
      val cluster1 = insertCluster(1, isPermanent = true)
      insertCluster(2)
      insertCluster(3)

      val result =
          service.replaceMonitoringPlot(
              observationId, cluster1[0], "why not", ReplacementDuration.LongTerm)

      assertEquals(cluster1.toSet(), result.removedMonitoringPlotIds, "Removed plot IDs")
      assertEquals(4, result.addedMonitoringPlotIds.size, "Number of plot IDs added")

      val plots = monitoringPlotsDao.findAll().associateBy { it.id!! }

      assertEquals(
          listOf<MonitoringPlotId?>(null, null, null, null),
          cluster1.map { plots[it]!!.permanentCluster },
          "Permanent cluster numbers of cluster whose plot was replaced")

      assertEquals(
          4,
          plots.values.count { it.permanentCluster == 1 },
          "Number of plots with permanent cluster number 1")

      assertFalse(plots[cluster1[0]]!!.isAvailable!!, "Replaced plot is available")

      plots.values
          .filter { it.id != cluster1[0] }
          .forEach { plot -> assertTrue(plot.isAvailable!!, "Plot ${plot.id} is available") }
    }

    @Test
    fun `removes permanent plot but keeps it available if this is the first observation and there are already completed plots`() {
      val cluster1 = insertCluster(1)
      insertObservationPlot(monitoringPlotId = cluster1[0], isPermanent = true)
      insertObservationPlot(monitoringPlotId = cluster1[1], isPermanent = true)
      insertObservationPlot(monitoringPlotId = cluster1[2], isPermanent = true)
      insertObservationPlot(
          monitoringPlotId = cluster1[3],
          isPermanent = true,
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          completedBy = user.userId,
          completedTime = Instant.EPOCH)

      val result =
          service.replaceMonitoringPlot(
              observationId, cluster1[0], "forest fire", ReplacementDuration.LongTerm)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = emptySet(), removedMonitoringPlotIds = setOf(cluster1[0])),
          result)

      assertEquals(
          true,
          monitoringPlotsDao.fetchOneById(cluster1[0])!!.isAvailable,
          "Monitoring plot should remain available")
      assertEquals(
          setOf(cluster1[1], cluster1[2], cluster1[3]),
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

      val cluster1 = insertCluster(1, isPermanent = true)

      val result =
          service.replaceMonitoringPlot(
              newObservationId, cluster1[0], "forest fire", ReplacementDuration.LongTerm)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = emptySet(), removedMonitoringPlotIds = setOf(cluster1[0])),
          result)

      assertEquals(
          true,
          monitoringPlotsDao.fetchOneById(cluster1[0])!!.isAvailable,
          "Monitoring plot should remain available")
      assertEquals(
          setOf(cluster1[1], cluster1[2], cluster1[3]),
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

  /**
   * Inserts a cluster of four plots arranged in the correct counterclockwise positions. By default,
   * the clusters are stacked northward, that is, cluster 1 is at y=0, cluster 2 is at y=2, and
   * cluster 3 is at y=4.
   *
   * Optionally also includes the cluster's plots in the most-recently-inserted observation.
   */
  private fun insertCluster(
      permanentCluster: Int,
      x: Int = 0,
      y: Int = (permanentCluster - 1) * 2,
      isPermanent: Boolean = false,
      insertObservationPlots: Boolean = isPermanent,
  ): List<MonitoringPlotId> {
    val plotIds =
        listOf(
            insertMonitoringPlot(
                permanentCluster = permanentCluster,
                permanentClusterSubplot = 1,
                x = x,
                y = y,
            ),
            insertMonitoringPlot(
                permanentCluster = permanentCluster,
                permanentClusterSubplot = 2,
                x = x + 1,
                y = y,
            ),
            insertMonitoringPlot(
                permanentCluster = permanentCluster,
                permanentClusterSubplot = 3,
                x = x + 1,
                y = y + 1,
            ),
            insertMonitoringPlot(
                permanentCluster = permanentCluster,
                permanentClusterSubplot = 4,
                x = x,
                y = y + 1,
            ),
        )

    if (insertObservationPlots) {
      plotIds.forEach { insertObservationPlot(monitoringPlotId = it, isPermanent = isPermanent) }
    }

    return plotIds
  }
}
