package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
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
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Known
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPhotosRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSiteSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedZoneSpeciesTotalsRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
import com.terraformation.backend.onePixelPng
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.db.InvalidObservationEndDateException
import com.terraformation.backend.tracking.db.InvalidObservationStartDateException
import com.terraformation.backend.tracking.db.ObservationAlreadyStartedException
import com.terraformation.backend.tracking.db.ObservationHasNoPlotsException
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import com.terraformation.backend.tracking.db.ObservationRescheduleStateException
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.ObservationTestHelper
import com.terraformation.backend.tracking.db.ObservationTestHelper.ObservationPlot
import com.terraformation.backend.tracking.db.ObservationTestHelper.ObservationZone
import com.terraformation.backend.tracking.db.ObservationTestHelper.PlantTotals
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.db.PlotAlreadyCompletedException
import com.terraformation.backend.tracking.db.PlotNotInObservationException
import com.terraformation.backend.tracking.db.ScheduleObservationWithoutPlantsException
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.event.ObservationPlotReplacedEvent
import com.terraformation.backend.tracking.event.ObservationRescheduledEvent
import com.terraformation.backend.tracking.event.ObservationScheduledEvent
import com.terraformation.backend.tracking.event.ObservationStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteMapEditedEvent
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NotificationCriteria
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.ReplacementDuration
import com.terraformation.backend.tracking.model.ReplacementResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import java.math.BigDecimal
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
        observationRequestedSubzonesDao,
        recordedPlantsDao)
  }
  private val plantingSiteStore: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        TestSingletons.countryDetector,
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
  private val helper: ObservationTestHelper by lazy {
    ObservationTestHelper(this, observationStore, user.userId)
  }

  private lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    plantingSiteId = insertPlantingSite(x = 0, width = 11, gridOrigin = point(1))

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
      //     Plot B - permanent cluster 3
      //     Plot C
      //     Plot D
      //   Subzone 2 (no plants)
      //     Plot E - permanent cluster 2
      //     Plot F
      //     Plot G
      // Zone 2 (2 permanent, 2 temporary)
      //   Subzone 3 (no plants)
      //     Plot H - permanent 1
      //
      // We should get:
      // - One permanent plot in subzone 1.
      // - Two temporary plots in subzone 1. The zone is configured for 3 temporary plots. 2 of them
      //   are spread evenly across the 2 subzones, and the remaining one is placed in the subzone
      //   with the fewest permanent plots that could potentially be included in the observation.
      //   Since permanent clusters 1 and 2 are spread evenly across subzones, preference is given
      //   to planted subzones, meaning subzone 1 is picked.
      // - Nothing from zone 2 because it has no plants.

      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(
          x = 0, width = 4, height = 1, numPermanentClusters = 2, numTemporaryPlots = 3)
      val subzone1Boundary =
          rectangle(width = 4 * MONITORING_PLOT_SIZE, height = MONITORING_PLOT_SIZE)
      insertPlantingSubzone(boundary = subzone1Boundary)
      insertPlanting()
      insertCluster(1)
      insertCluster(3, x = 1, y = 0)
      insertMonitoringPlot(x = 2, y = 0)
      insertMonitoringPlot(x = 3, y = 0)

      insertPlantingSubzone(x = 4, width = 3, height = 1)
      insertCluster(2, x = 4, y = 0)
      insertMonitoringPlot(x = 5, y = 0)
      insertMonitoringPlot(x = 6, y = 0)

      insertPlantingZone(
          x = 7, width = 3, height = 1, numPermanentClusters = 2, numTemporaryPlots = 2)
      insertPlantingSubzone(x = 7, width = 3, height = 1)
      insertCluster(1, x = 7, y = 0)

      val observationId = insertObservation(state = ObservationState.Upcoming)

      service.startObservation(observationId)

      val observationPlots = observationPlotsDao.findAll()
      val monitoringPlots = monitoringPlotsDao.findAll().associateBy { it.id }

      assertEquals(1, observationPlots.count { it.isPermanent!! }, "Number of permanent plots")
      assertEquals(2, observationPlots.count { !it.isPermanent!! }, "Number of temporary plots")

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
      // Zone 1 (2 permanent, 3 temporary)
      //   Subzone 1 (has plants)
      //   Subzone 2 (no plants)
      // Zone 2 (2 permanent, 2 temporary)
      //   Subzone 3 (no plants)
      //
      // In zone 1, the service should create two permanent clusters of one plot each. The plots
      // might both end up in subzone 1 or subzone 2, or there might be one plot in each subzone,
      // depending on the random number generator.
      //
      // The placement of the permanent plots will also determine how many temporary plots are
      // created and included in the observation.
      //
      // If both permanent plots are in subzone 1:
      // - They should both be included in the observation.
      // - We should get one temporary plot in subzone 1. The zone is configured for 3 temporary
      //   plots. 2 of them are spread evenly across the 2 subzones, and the remaining one is placed
      //   in the subzone with the fewest permanent plots, which is subzone 2, but subzone 2's plots
      //   are excluded because it has no plants.
      // If one permanent plot is in subzone 1 and the other in subzone 2:
      // - The plot in subzone 1 should be included in the observation, but not the one in subzone
      //   2, because we only include permanent clusters whose plots all lie in planted subzones.
      // - We should get two temporary plots in subzone 1. Two of zone 1's temporary plots are
      //   spread evenly across the two subzones, and the remaining plot is placed in the subzone
      //   with the fewest permanent plots, but in this case the subzones have the same number.
      //   As a tiebreaker, planted subzones are preferred over unplanted ones, which means we
      //   should choose subzone 1.
      // If both permanent plots are in subzone 2:
      // - Neither permanent plot should be included in the observation.
      // - We should get two temporary plots in subzone 1. Two of zone 1's temporary plots are
      //   spread evenly across the two subzones, and the remaining plot is placed in the subzone
      //   with the fewest temporary plots, which is subzone 1 in this case.
      //
      // In zone 2, the service should create two permanent plots, but neither of them should be
      // included in the observation since they all lie in an unplanted subzone.

      plantingSiteId = insertPlantingSite(x = 0, width = 14, gridOrigin = point(1))
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(
          x = 0, width = 6, height = 2, numPermanentClusters = 2, numTemporaryPlots = 3)
      val subzone1Boundary =
          rectangle(width = 3 * MONITORING_PLOT_SIZE, height = 2 * MONITORING_PLOT_SIZE)
      val subzone1Id = insertPlantingSubzone(boundary = subzone1Boundary)
      insertPlanting()

      val subzone2Id = insertPlantingSubzone(x = 3, width = 3, height = 2)

      insertPlantingZone(
          x = 6, width = 8, height = 2, numPermanentClusters = 2, numTemporaryPlots = 2)
      insertPlantingSubzone(x = 6, width = 8, height = 2)

      val observationId = insertObservation(state = ObservationState.Upcoming)

      // Make sure we actually get all the possible plot configurations.
      var got0PermanentPlotsInSubzone1 = false
      var got1PermanentPlotInSubzone1 = false
      var got2PermanentPlotsInSubzone1 = false
      val maxTestRuns = 100
      var testRuns = 0

      while (testRuns++ < maxTestRuns &&
          !(got0PermanentPlotsInSubzone1 &&
              got1PermanentPlotInSubzone1 &&
              got2PermanentPlotsInSubzone1)) {
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
            4,
            monitoringPlots.count { it.permanentCluster != null },
            "Total number of permanent plots created")
        assertEquals(
            2,
            numPermanentPlotsInSubzone1 + numPermanentPlotsInSubzone2,
            "Number of permanent plots created in zone 1")

        val expectedTemporaryPlots =
            when (numPermanentPlotsInSubzone1) {
              2 -> 1
              1 -> 2
              0 -> 2
              else ->
                  fail(
                      "Expected 0, 1, or 2 permanent plots in subzone $subzone1Id, but " +
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
          1 -> got1PermanentPlotInSubzone1 = true
          2 -> got2PermanentPlotsInSubzone1 = true
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
    fun `only includes plots in requested, planted subzones`() {
      // Given a planting site with this structure:
      //
      // +---------------------------------------------------------------------+
      // |                                Zone 1                               |
      // +-------------+-------------+-------------+-------------+-------------+
      // |  Subzone 1  |  Subzone 2  |  Subzone 3  |  Subzone 4  |  Subzone 5  |
      // |  (planted)  |  (planted)  | (unplanted) | (unplanted) |  (planted)  |
      // +-------------+-------------+-------------+-------------+-------------+
      //
      // Zone 1: 2 permanent clusters, 6 temporary plots
      //   Subzone 2: 2 permanent plots (half of cluster 1)
      //   Subzone 3: 2 permanent plots (other half of cluster 1)
      //   Subzone 5: 4 permanent plots (cluster 4)
      //
      // When the observation is requested for subzones 1, 2 and 3, the obseervation should include
      // two temporary plots in subzone 1 and one temporary plot in subzone 2:
      //
      // - Five temporary plots are allocated evenly across the subzones
      // - The remaining temporary plot is placed in the subzone with the fewest number of permanent
      //   plots (this is subzones 1, 4, and 5, with 0 each) with priority to planted and requested
      //   subzones (which means subzone 1 gets it, for a total of two plots including the one from
      //   the "evenly allocated across subzones" step).
      // - Subzones that are not both planted AND requested are not included in the observation:
      //   - Subzone 1 is both requested and planted, so it is included
      //   - Subzone 2 is both requested and planted, so it is included
      //   - Subzone 3 is requested but not planted
      //   - Subzone 4 is neither reqested nor planted
      //   - Subzone 5 is planted but not requested

      plantingSiteId = insertPlantingSite(x = 0, width = 15, gridOrigin = point(1))
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      insertPlantingZone(
          x = 0, width = 15, height = 2, numPermanentClusters = 2, numTemporaryPlots = 6)
      val subzoneIds =
          listOf(PlantingSubzoneId(0)) +
              (0..4).map { index -> insertPlantingSubzone(x = 3 * index, width = 3) }

      // Pre-existing permanent cluster straddling subzones 2 and 3
      insertMonitoringPlot(
          x = 5,
          y = 0,
          plantingSubzoneId = subzoneIds[2],
          permanentCluster = 1,
          permanentClusterSubplot = 1)
      insertMonitoringPlot(
          x = 5,
          y = 1,
          plantingSubzoneId = subzoneIds[2],
          permanentCluster = 1,
          permanentClusterSubplot = 2)
      insertMonitoringPlot(
          x = 6,
          y = 0,
          plantingSubzoneId = subzoneIds[3],
          permanentCluster = 1,
          permanentClusterSubplot = 3)
      insertMonitoringPlot(
          x = 6,
          y = 1,
          plantingSubzoneId = subzoneIds[3],
          permanentCluster = 1,
          permanentClusterSubplot = 4)

      // Pre-existing permanent cluster in subzone 5
      listOf(13 to 0, 13 to 1, 14 to 0, 14 to 1).forEachIndexed { index, (x, y) ->
        insertMonitoringPlot(
            x = x,
            y = y,
            plantingSubzoneId = subzoneIds[5],
            permanentCluster = 2,
            permanentClusterSubplot = index + 1)
      }

      insertWithdrawal()
      insertDelivery()
      insertPlanting(plantingSubzoneId = subzoneIds[1])
      insertWithdrawal()
      insertDelivery()
      insertPlanting(plantingSubzoneId = subzoneIds[2])
      insertWithdrawal()
      insertDelivery()
      insertPlanting(plantingSubzoneId = subzoneIds[5])

      val observationId = insertObservation(state = ObservationState.Upcoming)
      insertObservationRequestedSubzone(plantingSubzoneId = subzoneIds[1])
      insertObservationRequestedSubzone(plantingSubzoneId = subzoneIds[2])
      insertObservationRequestedSubzone(plantingSubzoneId = subzoneIds[3])

      service.startObservation(observationId)

      val subzoneIdsByMonitoringPlot =
          monitoringPlotsDao.findAll().associate { it.id to it.plantingSubzoneId }
      val observationPlots = observationPlotsDao.findAll()

      val numTemporaryPlotsBySubzone =
          observationPlots
              .filter { !it.isPermanent!! }
              .groupBy { subzoneIdsByMonitoringPlot[it.monitoringPlotId] }
              .mapValues { it.value.size }
      assertEquals(
          mapOf(subzoneIds[1] to 2, subzoneIds[2] to 1),
          numTemporaryPlotsBySubzone,
          "Number of temporary plots by subzone")

      val numPermanentPlotsBySubzone =
          observationPlots
              .filter { it.isPermanent!! }
              .groupBy { subzoneIdsByMonitoringPlot[it.monitoringPlotId] }
              .mapValues { it.value.size }
      assertEquals(
          emptyMap<PlantingSubzoneId, Int>(),
          numPermanentPlotsBySubzone,
          "Number of permanent plots by subzone")
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

      helper.insertPlantedSite(numPermanentClusters = 2, numTemporaryPlots = 3)

      val observationId =
          service.scheduleObservation(
              newObservationModel(
                  plantingSiteId = inserted.plantingSiteId,
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
      helper.insertPlantedSite()

      assertEquals(
          listOf(inserted.plantingSiteId),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria))
    }

    @Test
    fun `returns empty results with observations completed more recent than two weeks`() {
      helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results with observations completed earlier than two weeks but with other observations scheduled`() {
      helper.insertPlantedSite()

      insertObservation(state = ObservationState.Upcoming)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(2 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns sites with observations completed earlier than two weeks or have sub zone plantings`() {
      val plantingSiteIdWithCompletedObservation = helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(2 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      val anotherPlantingSiteIdWithCompletedObservation = helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(2 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      // planting site with a more recent completion
      helper.insertPlantedSite()
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      val plantingSiteIdWithPlantings = helper.insertPlantedSite()

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
      helper.insertPlantedSite(plantingCreatedTime = Instant.EPOCH.minus(7 * 4, ChronoUnit.DAYS))

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns site with subzone plantings planted earlier than 4 weeks and no completed observations`() {
      helper.insertPlantedSite(plantingCreatedTime = Instant.EPOCH.minus(7 * 4, ChronoUnit.DAYS))
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      assertEquals(
          listOf(inserted.plantingSiteId),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria))
    }

    @Test
    fun `returns empty results with observations completed more recent than six weeks`() {
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results with observations completed earlier than six weeks but with other observations scheduled`() {
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      insertObservation(state = ObservationState.Upcoming)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns sites with observations completed earlier than six weeks or have sub zone plantings`() {
      val plantingSiteIdWithCompletedObservation = helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      val anotherPlantingSiteIdWithCompletedObservation = helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      // planting site with a more recent completion
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      val plantingSiteIdWithPlantings =
          helper.insertPlantedSite(
              plantingCreatedTime = Instant.EPOCH.minus(4 * 7, ChronoUnit.DAYS))
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

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
      helper.insertPlantedSite(plantingCreatedTime = Instant.EPOCH.minus(7 * 6, ChronoUnit.DAYS))

      assertEquals(
          listOf(inserted.plantingSiteId),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria))
    }

    @Test
    fun `returns empty results with observations completed more recent than eight weeks`() {
      helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results with observations completed earlier than eight weeks but with other observations scheduled`() {
      helper.insertPlantedSite()

      insertObservation(state = ObservationState.Upcoming)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(8 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results if notification already sent`() {
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

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

      val plantingSiteIdWithCompletedObservation = helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(8 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      val anotherPlantingSiteIdWithCompletedObservation = helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(8 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      // planting site with a more recent completion
      helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      val plantingSiteIdWithPlantings =
          helper.insertPlantedSite(
              plantingCreatedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS))

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
      helper.insertPlantedSite(plantingCreatedTime = Instant.EPOCH.minus(7 * 14, ChronoUnit.DAYS))

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns site with subzone plantings planted earlier than 14 weeks and no completed observations`() {
      helper.insertPlantedSite(plantingCreatedTime = Instant.EPOCH.minus(7 * 14, ChronoUnit.DAYS))
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      assertEquals(
          listOf(inserted.plantingSiteId),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria))
    }

    @Test
    fun `returns empty results with observations completed more recent than sixteen weeks`() {
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results with observations completed earlier than sixteen weeks but with other observations scheduled`() {
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      insertObservation(state = ObservationState.Upcoming)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results if notification already sent`() {
      helper.insertPlantedSite()
      insertPlantingSiteNotification(
          type = NotificationType.ObservationNotScheduledSupport, number = 2)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      assertEquals(
          emptyList<Any>(), service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria))
    }

    @Test
    fun `returns sites with observations completed earlier than sixteen weeks or have sub zone plantings`() {
      val plantingSiteIdWithCompletedObservation = helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      val anotherPlantingSiteIdWithCompletedObservation = helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed)

      // planting site with a more recent completion
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH), state = ObservationState.Completed)

      val plantingSiteIdWithPlantings =
          helper.insertPlantedSite(
              plantingCreatedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS))
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

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
      helper.insertPlantedSite(width = 2, height = 7, subzoneCompletedTime = Instant.EPOCH)
      observationId = insertObservation()

      every { user.canManageObservation(observationId) } returns false
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
          listOf<MonitoringPlotId?>(null),
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
    fun `creates new temporary plot if needed`() {
      insertPlantingSubzone(width = 2, height = 1, plantingCompletedTime = Instant.EPOCH)
      val monitoringPlotId = insertMonitoringPlot(x = 0)
      insertObservationPlot()

      val result =
          service.replaceMonitoringPlot(
              observationId, monitoringPlotId, "Mudslide", ReplacementDuration.Temporary)

      val plots = monitoringPlotsDao.findAll()

      assertEquals(2, plots.size, "Number of monitoring plots after replacement")

      val otherPlotId = plots.first { it.id != monitoringPlotId }.id!!

      assertEquals(ReplacementResult(setOf(otherPlotId), setOf(monitoringPlotId)), result)

      assertEquals(
          listOf(otherPlotId),
          observationPlotsDao.findAll().map { it.monitoringPlotId },
          "Observation should only have replacement plot")
    }

    @Test
    fun `replaces entire permanent cluster if this is the first observation and there are no completed plots`() {
      insertPlantingZone(numPermanentClusters = 1, width = 2, height = 4)
      insertPlantingSubzone(width = 2, height = 4)
      insertWithdrawal()
      insertDelivery()
      insertPlanting()
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
          listOf(1),
          monitoringPlotsDao.fetchById(*cluster2.toTypedArray()).map { it.permanentCluster },
          "Should have moved second permanent cluster to first place")
      monitoringPlotsDao.fetchById(*cluster1.toTypedArray()).forEach { plot ->
        assertNotEquals(1, plot.permanentCluster, "Plot ${plot.id} cluster")
      }
    }

    @Test
    fun `removes permanent cluster if replacement cluster is in an unplanted subzone`() {
      insertPlantingZone(numPermanentClusters = 1, width = 1, height = 2)
      insertPlantingSubzone(width = 1, height = 1)
      insertWithdrawal()
      insertDelivery()
      insertPlanting()
      val cluster1 = insertCluster(1, isPermanent = true)

      insertPlantingSubzone(y = 1, width = 1, height = 1)
      val cluster2 = insertCluster(2)

      val result =
          service.replaceMonitoringPlot(
              observationId, cluster1[0], "why not", ReplacementDuration.Temporary)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = emptySet(), removedMonitoringPlotIds = cluster1.toSet()),
          result)

      assertEquals(
          listOf(1),
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

      testPermanentClusterReplacement(cluster1)
    }

    @Test
    fun `creates a new cluster to replace permanent plot if this is the first observation and duration is long-term`() {
      val cluster1 = insertCluster(1, isPermanent = true)

      testPermanentClusterReplacement(cluster1)
    }

    private fun testPermanentClusterReplacement(cluster1: List<MonitoringPlotId>) {
      val result =
          service.replaceMonitoringPlot(
              observationId, cluster1[0], "why not", ReplacementDuration.LongTerm)

      assertEquals(cluster1.toSet(), result.removedMonitoringPlotIds, "Removed plot IDs")
      assertEquals(1, result.addedMonitoringPlotIds.size, "Number of plot IDs added")

      val plots = monitoringPlotsDao.findAll().associateBy { it.id!! }

      assertEquals(
          listOf<MonitoringPlotId?>(null),
          cluster1.map { plots[it]!!.permanentCluster },
          "Permanent cluster numbers of cluster whose plot was replaced")

      assertEquals(
          1,
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
      val cluster2 = insertCluster(2)
      insertObservationPlot(monitoringPlotId = cluster1[0], isPermanent = true)
      insertObservationPlot(
          monitoringPlotId = cluster2[0],
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

  @Nested
  inner class OnPlantingSiteMapEditedEvent {
    private val plantingSite: ExistingPlantingSiteModel by lazy {
      plantingSiteStore.fetchSiteById(inserted.plantingSiteId, PlantingSiteDepth.Plot)
    }

    @BeforeEach
    fun setUp() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 1, numTemporaryPlots = 1, width = 2, height = 7)

      every { user.canReplaceObservationPlot(any()) } returns true
      every { user.canUpdatePlantingSite(any()) } returns true
    }

    @Test
    fun `does not replace plots in completed observation`() {
      insertPlantingSubzone(plantingCompletedTime = Instant.EPOCH, width = 2, height = 7)
      insertPlanting()
      insertObservation(completedTime = Instant.EPOCH)
      val permanentPlotIds =
          setOf(
              insertMonitoringPlot(isAvailable = false, x = 0, y = 8),
              insertMonitoringPlot(isAvailable = false, x = 1, y = 8),
              insertMonitoringPlot(isAvailable = false, x = 0, y = 9),
              insertMonitoringPlot(isAvailable = false, x = 1, y = 9),
          )
      permanentPlotIds.forEach { insertObservationPlot(monitoringPlotId = it, isPermanent = true) }

      val temporaryPlotId = insertMonitoringPlot(isAvailable = false, x = 2, y = 8)
      insertObservationPlot()

      val monitoringPlotIds = permanentPlotIds + temporaryPlotId

      val event =
          PlantingSiteMapEditedEvent(
              plantingSite,
              PlantingSiteEdit(BigDecimal.ONE, plantingSite, plantingSite, listOf()),
              ReplacementResult(emptySet(), monitoringPlotIds))

      service.on(event)

      assertEquals(
          monitoringPlotIds,
          observationPlotsDao
              .fetchByObservationId(inserted.observationId)
              .map { it.monitoringPlotId }
              .toSet(),
          "IDs of monitoring plots in observation")
    }

    // Plant total update logic is tested more comprehensively in ObservationStoreTest; this is
    // just to verify that the event handler actually updates the totals.
    @Test
    fun `subtracts removed plot plant counts from totals`() {
      insertPlantingSubzone(plantingCompletedTime = Instant.EPOCH, width = 3, height = 7)
      insertPlanting()
      val observationId = insertObservation()
      val remainingPlotId = insertMonitoringPlot()
      val removedPlotId = insertMonitoringPlot()

      val speciesId = inserted.speciesId
      val plantingZoneId = inserted.plantingZoneId

      helper.insertObservationScenario(
          ObservationZone(
              zoneId = plantingZoneId,
              plots =
                  listOf(
                      ObservationPlot(
                          remainingPlotId,
                          listOf(PlantTotals(speciesId, live = 3, dead = 2, existing = 1))),
                      ObservationPlot(
                          removedPlotId,
                          listOf(PlantTotals(speciesId, live = 4, dead = 5, existing = 6))))))

      val event =
          PlantingSiteMapEditedEvent(
              plantingSite,
              PlantingSiteEdit(BigDecimal.ONE, plantingSite, plantingSite, listOf()),
              ReplacementResult(emptySet(), setOf(removedPlotId)))

      service.on(event)

      helper.assertTotals(
          setOf(
              ObservedPlotSpeciesTotalsRow(
                  observationId, removedPlotId, speciesId, null, Known, 4, 5, 6, 56, 5, 4),
              ObservedPlotSpeciesTotalsRow(
                  observationId, remainingPlotId, speciesId, null, Known, 3, 2, 1, 40, 2, 3),
              ObservedZoneSpeciesTotalsRow(
                  observationId, plantingZoneId, speciesId, null, Known, 3, 2, 1, 40, 2, 3),
              ObservedSiteSpeciesTotalsRow(
                  observationId, plantingSiteId, speciesId, null, Known, 3, 2, 1, 40, 2, 3),
          ),
          "Totals after removal")
    }

    @Test
    fun `creates new temporary plot in observation if existing one is removed from site`() {
      insertPlantingSubzone(plantingCompletedTime = Instant.EPOCH, width = 2, height = 7)
      insertPlanting()
      val replacedMonitoringPlotId = insertMonitoringPlot(isAvailable = false, x = 0, y = 8)
      insertObservation()
      insertObservationPlot()

      val event =
          PlantingSiteMapEditedEvent(
              plantingSite,
              PlantingSiteEdit(BigDecimal.ONE, plantingSite, plantingSite, listOf()),
              ReplacementResult(emptySet(), setOf(inserted.monitoringPlotId)))

      service.on(event)

      val observationPlots = observationPlotsDao.findAll()
      val temporaryPlots = observationPlots.filterNot { it.isPermanent!! }

      assertEquals(1, temporaryPlots.size, "Number of temporary plots")
      assertNotEquals(
          replacedMonitoringPlotId, temporaryPlots[0].monitoringPlotId, "ID of temporary plot")
    }

    @Test
    fun `creates new permanent cluster in observation if existing one is removed from site`() {
      insertPlantingSubzone(plantingCompletedTime = Instant.EPOCH, width = 2, height = 7)
      insertPlanting()
      insertObservation()
      val originalPermanentPlotIds =
          setOf(
              insertMonitoringPlot(isAvailable = true, x = 0, y = 8),
              insertMonitoringPlot(isAvailable = true, x = 1, y = 8),
              insertMonitoringPlot(isAvailable = false, x = 0, y = 9),
              insertMonitoringPlot(isAvailable = false, x = 1, y = 9),
          )
      originalPermanentPlotIds.forEach {
        insertObservationPlot(monitoringPlotId = it, isPermanent = true)
      }

      val event =
          PlantingSiteMapEditedEvent(
              plantingSite,
              PlantingSiteEdit(BigDecimal.ONE, plantingSite, plantingSite, listOf()),
              ReplacementResult(emptySet(), originalPermanentPlotIds))

      service.on(event)

      val observationPlots = observationPlotsDao.fetchByObservationId(inserted.observationId)

      assertEquals(
          1,
          observationPlots.filter { it.isPermanent!! }.size,
          "Number of permanent plots in observation")
      assertEquals(
          emptySet<MonitoringPlotId>(),
          observationPlots.map { it.monitoringPlotId }.toSet().intersect(originalPermanentPlotIds),
          "Permanent plots remaining in observation")
    }

    @Test
    fun `adds new permanent cluster to observation if it already exists`() {
      insertPlantingSubzone(plantingCompletedTime = Instant.EPOCH, width = 2, height = 7)
      insertPlanting()
      insertObservation()
      val newPermanentPlotIds =
          setOf(
              insertMonitoringPlot(x = 0, y = 8, permanentCluster = 1, permanentClusterSubplot = 1),
          )

      val event =
          PlantingSiteMapEditedEvent(
              plantingSite,
              PlantingSiteEdit(BigDecimal.ONE, plantingSite, plantingSite, listOf()),
              ReplacementResult(newPermanentPlotIds, emptySet()))

      service.on(event)

      val observationPlots = observationPlotsDao.fetchByObservationId(inserted.observationId)

      assertEquals(
          newPermanentPlotIds,
          observationPlots.map { it.monitoringPlotId }.toSet(),
          "Permanent plots in observation")
    }

    @Test
    fun `does not add new permanent cluster to observation if it is in an unplanted subzone`() {
      plantingZonesDao.update(
          plantingZonesDao.fetchOneById(inserted.plantingZoneId)!!.copy(numPermanentClusters = 2))
      insertPlantingSubzone(plantingCompletedTime = Instant.EPOCH, width = 2, height = 2)
      insertPlanting()

      // The planted subzone already has a cluster, so any new one we create has to go somewhere
      // else.
      val plotIdsInPlantedSubzone =
          setOf(
              insertMonitoringPlot(
                  isAvailable = true,
                  x = 0,
                  y = 0,
                  permanentCluster = 1,
                  permanentClusterSubplot = 1),
              insertMonitoringPlot(
                  isAvailable = true,
                  x = 1,
                  y = 0,
                  permanentCluster = 1,
                  permanentClusterSubplot = 2),
              insertMonitoringPlot(
                  isAvailable = true,
                  x = 0,
                  y = 1,
                  permanentCluster = 1,
                  permanentClusterSubplot = 3),
              insertMonitoringPlot(
                  isAvailable = true,
                  x = 1,
                  y = 1,
                  permanentCluster = 1,
                  permanentClusterSubplot = 4),
          )

      val plotIdsInRemovedArea =
          setOf(
              insertMonitoringPlot(
                  isAvailable = false,
                  x = 2,
                  y = 0,
                  permanentCluster = 2,
                  permanentClusterSubplot = 1),
              insertMonitoringPlot(
                  isAvailable = false,
                  x = 3,
                  y = 0,
                  permanentCluster = 2,
                  permanentClusterSubplot = 2),
              insertMonitoringPlot(
                  isAvailable = false,
                  x = 2,
                  y = 1,
                  permanentCluster = 2,
                  permanentClusterSubplot = 3),
              insertMonitoringPlot(
                  isAvailable = false,
                  x = 3,
                  y = 1,
                  permanentCluster = 2,
                  permanentClusterSubplot = 4),
          )

      insertObservation()
      (plotIdsInRemovedArea + plotIdsInPlantedSubzone).forEach {
        insertObservationPlot(monitoringPlotId = it, isPermanent = true)
      }

      // Second subzone is the only place with room for a new cluster, but it isn't planted.
      insertPlantingSubzone(
          plantingCompletedTime = Instant.EPOCH, x = 0, y = 2, width = 2, height = 5)

      val event =
          PlantingSiteMapEditedEvent(
              plantingSite,
              PlantingSiteEdit(BigDecimal.ONE, plantingSite, plantingSite, listOf()),
              ReplacementResult(emptySet(), plotIdsInRemovedArea))

      service.on(event)

      assertEquals(
          plotIdsInPlantedSubzone,
          observationPlotsDao
              .fetchByObservationId(inserted.observationId)
              .map { it.monitoringPlotId }
              .toSet(),
          "Plot IDs in observation")
    }
  }

  /**
   * Inserts a permanent monitoring plot with a given cluster number. By default, the clusters are
   * stacked northward, that is, cluster 1 is at y=0, cluster 2 is at y=1, and cluster 3 is at y=2.
   *
   * Optionally also includes the cluster's plot in the most-recently-inserted observation.
   */
  private fun insertCluster(
      permanentCluster: Int,
      x: Int = 0,
      y: Int = (permanentCluster - 1) * 2,
      isPermanent: Boolean = false,
      insertObservationPlots: Boolean = isPermanent,
  ): List<MonitoringPlotId> {
    val plotId =
        insertMonitoringPlot(
            permanentCluster = permanentCluster,
            permanentClusterSubplot = 1,
            x = x,
            y = y,
        )

    if (insertObservationPlots) {
      insertObservationPlot(isPermanent = isPermanent)
    }

    return listOf(plotId)
  }
}
