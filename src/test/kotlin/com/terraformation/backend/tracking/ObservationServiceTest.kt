package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPhotoType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Known
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Other
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Unknown
import com.terraformation.backend.db.tracking.embeddables.pojos.ObservationPlotId
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationPhotosRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationsRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.i18n.TimeZones
import com.terraformation.backend.onePixelPng
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.db.InvalidObservationEndDateException
import com.terraformation.backend.tracking.db.InvalidObservationStartDateException
import com.terraformation.backend.tracking.db.ObservationAlreadyStartedException
import com.terraformation.backend.tracking.db.ObservationHasNoSubzonesException
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import com.terraformation.backend.tracking.db.ObservationRescheduleStateException
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.ObservationTestHelper
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.db.PlotAlreadyCompletedException
import com.terraformation.backend.tracking.db.PlotNotInObservationException
import com.terraformation.backend.tracking.db.PlotSizeNotReplaceableException
import com.terraformation.backend.tracking.db.ScheduleObservationWithoutPlantsException
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingZoneEdit
import com.terraformation.backend.tracking.event.ObservationPlotReplacedEvent
import com.terraformation.backend.tracking.event.ObservationRescheduledEvent
import com.terraformation.backend.tracking.event.ObservationScheduledEvent
import com.terraformation.backend.tracking.event.ObservationStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteMapEditedEvent
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.NewBiomassDetailsModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NotificationCriteria
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.ReplacementDuration
import com.terraformation.backend.tracking.model.ReplacementResult
import com.terraformation.backend.util.Turtle
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
import org.junit.jupiter.api.Assertions.assertNull
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

class ObservationServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

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
        parentStore,
        recordedPlantsDao)
  }
  private val plantingSiteStore: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        TestSingletons.countryDetector,
        dslContext,
        TestEventPublisher(),
        IdentifierGenerator(clock, dslContext),
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
        monitoringPlotsDao,
        observationPhotosDao,
        observationStore,
        plantingSiteStore,
        parentStore,
        SystemUser(usersDao),
    )
  }
  private val helper: ObservationTestHelper by lazy {
    ObservationTestHelper(this, observationStore, user.userId)
  }

  private lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(role = Role.Admin)
    plantingSiteId = insertPlantingSite(x = 0, width = 11, gridOrigin = point(1))
  }

  @Nested
  inner class StartObservation {
    @BeforeEach
    fun setUp() {
      insertUserGlobalRole(role = GlobalRole.SuperAdmin)
    }

    @Test
    fun `assigns correct plots to planting zones`() {
      // Given a planting site with this structure:
      //
      // Zone 1 (2 permanent, 3 temporary)
      //   Subzone 1
      //     Plot A - permanent cluster 1
      //     Plot B - permanent cluster 3
      //     Plot C
      //     Plot D
      //   Subzone 2
      //     Plot E - permanent cluster 2
      //     Plot F
      //     Plot G
      // Zone 2 (2 permanent, 2 temporary)
      //   Subzone 3
      //     Plot H - permanent 1
      //
      // When we start an observation with subzone 1 requested, we should get:
      // - One permanent plot in subzone 1.
      // - Two temporary plots in subzone 1. The zone is configured for 3 temporary plots. 2 of them
      //   are spread evenly across the 2 subzones, and the remaining one is placed in the subzone
      //   with the fewest permanent plots that could potentially be included in the observation.
      //   Since permanent clusters 1 and 2 are spread evenly across subzones, preference is given
      //   to requested subzones, meaning subzone 1 is picked.
      // - Nothing from zone 2 because it was not requested.

      insertFacility(type = FacilityType.Nursery)

      insertPlantingZone(x = 0, width = 4, height = 1, numPermanentPlots = 2, numTemporaryPlots = 3)
      val subzone1Boundary =
          rectangle(width = 4 * MONITORING_PLOT_SIZE, height = MONITORING_PLOT_SIZE)
      val plantingSubzoneId1 = insertPlantingSubzone(boundary = subzone1Boundary)
      insertPermanentPlot(1)
      insertPermanentPlot(3, x = 1, y = 0)
      insertMonitoringPlot(x = 2, y = 0)
      insertMonitoringPlot(x = 3, y = 0)

      insertPlantingSubzone(x = 4, width = 3, height = 1)
      insertPermanentPlot(2, x = 4, y = 0)
      insertMonitoringPlot(x = 5, y = 0)
      insertMonitoringPlot(x = 6, y = 0)

      insertPlantingZone(x = 7, width = 3, height = 1, numPermanentPlots = 2, numTemporaryPlots = 2)
      insertPlantingSubzone(x = 7, width = 3, height = 1)
      insertPermanentPlot(1, x = 7, y = 0)

      val observationId = insertObservation(state = ObservationState.Upcoming)
      insertObservationRequestedSubzone(plantingSubzoneId = plantingSubzoneId1)

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
      // Zone 1 (2 permanent, 3 temporary)
      //   Subzone 1
      //   Subzone 2
      // Zone 2 (2 permanent, 2 temporary)
      //   Subzone 3
      //
      // An observation is started with subzone 1 requested.
      //
      // In zone 1, the service should create two permanent plots. The plots might both end up in
      // subzone 1 or subzone 2, or there might be one plot in each subzone, depending on the random
      // number generator.
      //
      // The placement of the permanent plots will also determine how many temporary plots are
      // created and included in the observation.
      //
      // If both permanent plots are in subzone 1:
      // - They should both be included in the observation.
      // - We should get one temporary plot in subzone 1. The zone is configured for 3 temporary
      //   plots. 2 of them are spread evenly across the 2 subzones, and the remaining one is placed
      //   in the subzone with the fewest permanent plots, which is subzone 2, but subzone 2's plots
      //   are excluded because it wasn't requested.
      // If one permanent plot is in subzone 1 and the other in subzone 2:
      // - The plot in subzone 1 should be included in the observation, but not the one in subzone
      //   2, because we only include permanent plots in requested subzones.
      // - We should get two temporary plots in subzone 1. Two of zone 1's temporary plots are
      //   spread evenly across the two subzones, and the remaining plot is placed in the subzone
      //   with the fewest permanent plots, but in this case the subzones have the same number.
      //   As a tiebreaker, requested subzones are preferred over unrequested ones, which means we
      //   should choose subzone 1.
      // If both permanent plots are in subzone 2:
      // - Neither permanent plot should be included in the observation.
      // - We should get two temporary plots in subzone 1. Two of zone 1's temporary plots are
      //   spread evenly across the two subzones, and the remaining plot is placed in the subzone
      //   with the fewest temporary plots, which is subzone 1 in this case.
      //
      // In zone 2, the service should create two permanent plots, but neither of them should be
      // included in the observation since they all lie in an unrequested subzone.

      plantingSiteId = insertPlantingSite(x = 0, width = 14, gridOrigin = point(1))
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      insertPlantingZone(x = 0, width = 6, height = 2, numPermanentPlots = 2, numTemporaryPlots = 3)
      val subzone1Boundary =
          rectangle(width = 3 * MONITORING_PLOT_SIZE, height = 2 * MONITORING_PLOT_SIZE)
      val subzone1Id = insertPlantingSubzone(boundary = subzone1Boundary)

      val subzone2Id = insertPlantingSubzone(x = 3, width = 3, height = 2)

      insertPlantingZone(x = 6, width = 8, height = 2, numPermanentPlots = 2, numTemporaryPlots = 2)
      insertPlantingSubzone(x = 6, width = 8, height = 2)

      val observationId = insertObservation(state = ObservationState.Upcoming)
      insertObservationRequestedSubzone(plantingSubzoneId = subzone1Id)

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
    fun `only includes plots in requested subzones`() {
      // Given a planting site with this structure:
      //
      // +---------------------------------------------------------------------+
      // |                                Zone 1                               |
      // +-------------+-------------+-------------+-------------+-------------+
      // |  Subzone 1  |  Subzone 2  |  Subzone 3  |  Subzone 4  |  Subzone 5  |
      // +-------------+-------------+-------------+-------------+-------------+
      //
      // Zone 1: 8 permanent plots, 6 temporary plots
      //   Subzone 2: 2 permanent plots
      //   Subzone 3: 2 permanent plots
      //   Subzone 5: 4 permanent plots
      //
      // When the observation is requested for subzones 1 and 2, the observation should include
      // two temporary plots in subzone 1 and one temporary plot in subzone 2:
      //
      // - Five temporary plots are allocated evenly across the subzones
      // - The remaining temporary plot is placed in the subzone with the fewest number of permanent
      //   plots (this is subzones 1 and 4, with 0 each) with priority to requested subzones (which
      //   means subzone 1 gets it, for a total of two plots including the one from the "evenly
      //   allocated across subzones" step).
      // - Subzones that are not requested are not included in the observation, meaning we only
      //   include the plots in subzones 1 and 2.

      plantingSiteId = insertPlantingSite(x = 0, width = 15, gridOrigin = point(1))
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      insertPlantingZone(
          x = 0, width = 15, height = 2, numPermanentPlots = 8, numTemporaryPlots = 6)
      val subzoneIds =
          listOf(PlantingSubzoneId(0)) +
              (0..4).map { index -> insertPlantingSubzone(x = 3 * index, width = 3) }

      // Pre-existing permanent plots in subzones 2, 3, and 5
      listOf(5 to 0, 5 to 1, 7 to 0, 7 to 1, 13 to 0, 13 to 1, 14 to 0, 14 to 1).forEachIndexed {
          index,
          (x, y) ->
        insertMonitoringPlot(
            x = x, y = y, plantingSubzoneId = subzoneIds[x / 3 + 1], permanentCluster = index + 1)
      }

      val observationId = insertObservation(state = ObservationState.Upcoming)
      insertObservationRequestedSubzone(plantingSubzoneId = subzoneIds[1])
      insertObservationRequestedSubzone(plantingSubzoneId = subzoneIds[2])

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
          mapOf(subzoneIds[2] to 2),
          numPermanentPlotsBySubzone,
          "Number of permanent plots by subzone")
    }

    @Test
    fun `sets planting site history ID`() {
      val boundary = rectangle(width = 4 * MONITORING_PLOT_SIZE, height = MONITORING_PLOT_SIZE)

      insertPlantingZone(boundary = boundary, numPermanentPlots = 1, numTemporaryPlots = 1)
      insertPlantingSubzone(boundary = boundary)

      val observationId = insertObservation(state = ObservationState.Upcoming)
      insertObservationRequestedSubzone()

      val updatedSiteHistoryId = insertPlantingSiteHistory()
      insertPlantingZoneHistory()
      insertPlantingSubzoneHistory()

      service.startObservation(observationId)

      assertEquals(
          updatedSiteHistoryId,
          observationsDao.fetchOneById(observationId)?.plantingSiteHistoryId,
          "Planting site history ID")
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
    fun `throws exception if observation has no requested subzones`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)

      assertThrows<ObservationHasNoSubzonesException> { service.startObservation(observationId) }
    }

    @Test
    fun `throws exception if no permission to manage observation`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)

      deleteUserGlobalRole(role = GlobalRole.SuperAdmin)

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
        deleteOrganizationUser()

        assertThrows<ObservationNotFoundException> {
          service.readPhoto(observationId, plotId, fileId)
        }
      }
    }

    @Nested
    inner class StorePhoto {
      @Test
      fun `associates photo with observation and plot`() {
        val fileId1 =
            service.storePhoto(
                observationId,
                plotId,
                point(1),
                ObservationPlotPosition.NortheastCorner,
                byteArrayOf(1).inputStream(),
                metadata)

        val fileId2 =
            service.storePhoto(
                observationId,
                plotId,
                point(1),
                null,
                byteArrayOf(1).inputStream(),
                metadata,
                ObservationPhotoType.Soil)

        fileStore.assertFileExists(filesDao.fetchOneById(fileId1)!!.storageUrl!!)
        fileStore.assertFileExists(filesDao.fetchOneById(fileId2)!!.storageUrl!!)

        assertTableEquals(
            listOf(
                ObservationPhotosRecord(
                    fileId1,
                    observationId,
                    plotId,
                    ObservationPlotPosition.NortheastCorner,
                    point(1),
                    ObservationPhotoType.Plot),
                ObservationPhotosRecord(
                    fileId2, observationId, plotId, null, point(1), ObservationPhotoType.Soil),
            ))
      }

      @Test
      fun `throws exception for missing photo position for Quadrat photos`() {
        assertThrows<IllegalArgumentException> {
          service.storePhoto(
              observationId,
              plotId,
              point(1),
              null,
              byteArrayOf(1).inputStream(),
              metadata,
              ObservationPhotoType.Quadrat)
        }
      }

      @Test
      fun `throws exception for providing photo position for Soil photos`() {
        assertThrows<IllegalArgumentException> {
          service.storePhoto(
              observationId,
              plotId,
              point(1),
              ObservationPlotPosition.SoutheastCorner,
              byteArrayOf(1).inputStream(),
              metadata,
              ObservationPhotoType.Soil)
        }
      }

      @Test
      fun `throws exception if no permission to update observation`() {
        deleteOrganizationUser()

        assertThrows<ObservationNotFoundException> {
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
      insertOrganizationUser(role = Role.Manager)

      assertThrows<AccessDeniedException> {
        service.scheduleObservation(newObservationModel(plantingSiteId = plantingSiteId))
      }
    }

    @Test
    fun `throws planting site not found exception scheduling an observation if no permission to schedule observation or read the planting site`() {
      deleteOrganizationUser()

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

      helper.insertPlantedSite(numPermanentPlots = 2, numTemporaryPlots = 3)

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
            isAdHoc = false,
            observationType = ObservationType.Monitoring,
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
      insertOrganizationUser(role = Role.Manager)

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      assertThrows<AccessDeniedException> {
        service.rescheduleObservation(observationId, startDate, endDate)
      }
    }

    @Test
    fun `throws observation not found exception rescheduling an observation if no permission to reschedule or read observation`() {
      deleteOrganizationUser()

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
      insertPlantingZone(numPermanentPlots = 1, numTemporaryPlots = 1)
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
      insertPlantingSite(x = 0)
      insertPlantingZone(numPermanentPlots = 1, numTemporaryPlots = 1)
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
      assertTableEmpty(OBSERVATION_PLOTS, "Observation plots should be removed")

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
      switchToUser(insertUser(type = UserType.System))
    }

    @Test
    fun `throws exception when no permission to manage notifications`() {
      switchToUser(insertUser())
      insertUserGlobalRole(role = GlobalRole.SuperAdmin)

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
      switchToUser(insertUser(type = UserType.System))
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
      switchToUser(insertUser(type = UserType.System))
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
      switchToUser(insertUser(type = UserType.System))
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
      insertObservationRequestedSubzone()
    }

    @Test
    fun `marks temporary plot as unavailable and clears its cluster number if duration is long-term`() {
      val plotId1 = insertPermanentPlot(1)
      insertPermanentPlot(2)
      insertPermanentPlot(3)
      insertObservationPlot(monitoringPlotId = plotId1)

      service.replaceMonitoringPlot(
          observationId, plotId1, "Meteor strike", ReplacementDuration.LongTerm)

      val plots = monitoringPlotsDao.findAll().associateBy { it.id!! }

      assertNull(plots[plotId1]!!.permanentCluster, "Cluster number of plot that was replaced")

      assertFalse(plots[plotId1]!!.isAvailable!!, "Replaced plot is available")

      plots.values
          .filter { it.id != plotId1 }
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

      assertTableEmpty(OBSERVATION_PLOTS, "Observation should not have any plots")
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
    fun `replaces permanent plot if this is the first observation and there are no completed plots`() {
      insertPlantingZone(numPermanentPlots = 1, width = 2, height = 4)
      insertPlantingSubzone(width = 2, height = 4)
      insertObservationRequestedSubzone()
      val plotId1 = insertPermanentPlot(1, isPermanent = true)
      val plotId2 = insertPermanentPlot(2)

      val result =
          service.replaceMonitoringPlot(
              observationId, plotId1, "why not", ReplacementDuration.Temporary)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = setOf(plotId2), removedMonitoringPlotIds = setOf(plotId1)),
          result)

      assertEquals(
          1,
          monitoringPlotsDao.fetchOneById(plotId2)!!.permanentCluster,
          "Should have moved second permanent cluster to first place")
      assertNotEquals(
          1, monitoringPlotsDao.fetchOneById(plotId1)!!.permanentCluster, "Plot $plotId1 cluster")
    }

    @Test
    fun `removes cluster number if replacement plot is in an unrequested subzone`() {
      insertPlantingZone(numPermanentPlots = 1, width = 1, height = 2)
      insertPlantingSubzone(width = 1, height = 1)
      insertObservationRequestedSubzone()
      val plotId1 = insertPermanentPlot(1, isPermanent = true)

      insertPlantingSubzone(y = 1, width = 1, height = 1)
      val plotId2 = insertPermanentPlot(2)

      val result =
          service.replaceMonitoringPlot(
              observationId, plotId1, "why not", ReplacementDuration.Temporary)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = emptySet(), removedMonitoringPlotIds = setOf(plotId1)),
          result)

      assertEquals(
          1,
          monitoringPlotsDao.fetchOneById(plotId2)!!.permanentCluster,
          "Should have moved second permanent cluster to first place")
      assertNotEquals(
          1, monitoringPlotsDao.fetchOneById(plotId1)!!.permanentCluster, "Plot $plotId1 cluster")
    }

    @Test
    fun `marks permanent plot as unavailable and swaps in a new one if this is the first observation and duration is long-term`() {
      val plotId1 = insertPermanentPlot(1, isPermanent = true)
      insertPermanentPlot(2)
      insertPermanentPlot(3)

      testPermanentPlotReplacement(plotId1)
    }

    @Test
    fun `creates a new plot to replace permanent plot if this is the first observation and duration is long-term`() {
      val plotId1 = insertPermanentPlot(1, isPermanent = true)

      testPermanentPlotReplacement(plotId1)
    }

    private fun testPermanentPlotReplacement(plotId1: MonitoringPlotId) {
      val result =
          service.replaceMonitoringPlot(
              observationId, plotId1, "why not", ReplacementDuration.LongTerm)

      assertEquals(setOf(plotId1), result.removedMonitoringPlotIds, "Removed plot IDs")
      assertEquals(1, result.addedMonitoringPlotIds.size, "Number of plot IDs added")

      val plots = monitoringPlotsDao.findAll().associateBy { it.id!! }

      assertNull(
          plots[plotId1]!!.permanentCluster, "Permanent cluster number of plot that was replaced")

      assertEquals(
          1,
          plots.values.count { it.permanentCluster == 1 },
          "Number of plots with permanent cluster number 1")

      assertFalse(plots[plotId1]!!.isAvailable!!, "Replaced plot is available")

      plots.values
          .filter { it.id != plotId1 }
          .forEach { plot -> assertTrue(plot.isAvailable!!, "Plot ${plot.id} is available") }
    }

    @Test
    fun `removes permanent plot but keeps it available if this is the first observation and there are already completed plots`() {
      val plotId1 = insertPermanentPlot(1)
      val plotId2 = insertPermanentPlot(2)
      insertObservationPlot(monitoringPlotId = plotId1, isPermanent = true)
      insertObservationPlot(
          monitoringPlotId = plotId2,
          isPermanent = true,
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          completedBy = user.userId,
          completedTime = Instant.EPOCH)

      val result =
          service.replaceMonitoringPlot(
              observationId, plotId1, "forest fire", ReplacementDuration.LongTerm)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = emptySet(), removedMonitoringPlotIds = setOf(plotId1)),
          result)

      assertEquals(
          true,
          monitoringPlotsDao.fetchOneById(plotId1)!!.isAvailable,
          "Monitoring plot should remain available")
    }

    @Test
    fun `removes permanent plot but keeps it available if this is not the first observation`() {
      observationsDao.update(
          observationsDao
              .fetchOneById(inserted.observationId)!!
              .copy(completedTime = Instant.EPOCH, stateId = ObservationState.Completed))

      val newObservationId = insertObservation()

      val plotId1 = insertPermanentPlot(1, isPermanent = true)

      val result =
          service.replaceMonitoringPlot(
              newObservationId, plotId1, "forest fire", ReplacementDuration.LongTerm)

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = emptySet(), removedMonitoringPlotIds = setOf(plotId1)),
          result)

      assertEquals(
          true,
          monitoringPlotsDao.fetchOneById(plotId1)!!.isAvailable,
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
              isAdHoc = false,
              observationType = ObservationType.Monitoring,
              plantingSiteHistoryId = inserted.plantingSiteHistoryId,
              plantingSiteId = inserted.plantingSiteId,
              requestedSubzoneIds = setOf(inserted.plantingSubzoneId),
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
    fun `throws exception if plot size is different from the size of newly-created plots`() {
      val monitoringPlotId = insertMonitoringPlot(sizeMeters = MONITORING_PLOT_SIZE_INT - 1)
      insertObservationPlot()

      assertThrows<PlotSizeNotReplaceableException> {
        service.replaceMonitoringPlot(
            observationId, monitoringPlotId, "justification", ReplacementDuration.LongTerm)
      }
    }

    @Test
    fun `throws illegal state exception if replacing ad-hoc observation plot`() {
      val observationId = insertObservation(isAdHoc = true)
      val monitoringPlotId = insertMonitoringPlot()

      assertThrows<IllegalStateException> {
        service.replaceMonitoringPlot(
            observationId, monitoringPlotId, "justification", ReplacementDuration.LongTerm)
      }
    }

    @Test
    fun `throws access denied exception if no permission to replace plot`() {
      val monitoringPlotId = insertMonitoringPlot()
      insertObservationPlot()

      insertOrganizationUser(role = Role.Manager)

      assertThrows<AccessDeniedException> {
        service.replaceMonitoringPlot(
            observationId, monitoringPlotId, "justification", ReplacementDuration.LongTerm)
      }
    }
  }

  @Nested
  inner class CompleteAdHocObservation {
    @BeforeEach
    fun setUp() {
      insertOrganizationUser(role = Role.Contributor)
    }

    @Test
    fun `creates new completed observation, plot and plant rows for monitoring observations`() {
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()

      val observedTime = Instant.ofEpochSecond(1)
      clock.instant = Instant.ofEpochSecond(123)

      val date = LocalDate.ofInstant(observedTime, TimeZones.UTC)
      val recordedPlants =
          listOf(
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Live),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Live),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Dead),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Existing),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId2,
                  statusId = RecordedPlantStatus.Existing,
              ),
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = point(1),
                  speciesName = "Other 1",
                  statusId = RecordedPlantStatus.Existing,
              ),
              RecordedPlantsRow(
                  certaintyId = Other,
                  gpsCoordinates = point(1),
                  speciesName = "Other 2",
                  statusId = RecordedPlantStatus.Dead,
              ),
              RecordedPlantsRow(
                  certaintyId = Unknown,
                  gpsCoordinates = point(1),
                  statusId = RecordedPlantStatus.Live,
              ),
          )

      val (observationId, plotId) =
          service.completeAdHocObservation(
              null,
              emptySet(),
              "Notes",
              observedTime,
              ObservationType.Monitoring,
              plantingSiteId,
              recordedPlants,
              point(1),
          )

      assertTableEquals(
          ObservationsRecord(
              completedTime = clock.instant,
              createdTime = clock.instant,
              endDate = date,
              id = observationId,
              isAdHoc = true,
              observationTypeId = ObservationType.Monitoring,
              plantingSiteId = plantingSiteId,
              plantingSiteHistoryId = inserted.plantingSiteHistoryId,
              startDate = date,
              stateId = ObservationState.Completed,
          ),
          "Observation table")

      val plotBoundary = Turtle(point(1)).makePolygon { square(MONITORING_PLOT_SIZE_INT) }

      val actual = monitoringPlotsDao.fetchOneById(plotId)
      assertEquals(
          MonitoringPlotsRow(
              createdBy = user.userId,
              createdTime = clock.instant,
              id = plotId,
              isAdHoc = true,
              isAvailable = false,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              organizationId = inserted.organizationId,
              plantingSiteId = plantingSiteId,
              plotNumber = 1,
              sizeMeters = MONITORING_PLOT_SIZE_INT,
          ),
          actual?.copy(boundary = null),
          "Ad-hoc plot row")
      assertGeometryEquals(plotBoundary, actual?.boundary, "Ad-hoc plot boundary")

      val latestPlotHistoryId =
          monitoringPlotHistoriesDao.fetchByMonitoringPlotId(plotId).maxOf { it.id!! }

      assertEquals(
          ObservationPlotsRow(
              observationId = observationId,
              monitoringPlotId = plotId,
              claimedBy = user.userId,
              claimedTime = clock.instant,
              completedBy = user.userId,
              completedTime = clock.instant,
              createdBy = user.userId,
              createdTime = clock.instant,
              isPermanent = false,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              observedTime = observedTime,
              notes = "Notes",
              statusId = ObservationPlotStatus.Completed,
              monitoringPlotHistoryId = latestPlotHistoryId,
          ),
          observationPlotsDao
              .fetchByObservationPlotId(ObservationPlotId(observationId, plotId))
              .single(),
          "Observation plot row")

      val plotSpecies1Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, plotId, speciesId1, null, Known, 2, 1, 1, null, 0, 0)
      val plotSpecies2Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId, plotId, speciesId2, null, Known, 0, 0, 1, null, 0, 0)
      val plotOther1Total =
          ObservedPlotSpeciesTotalsRow(
              observationId, plotId, null, "Other 1", Other, 0, 0, 1, null, 0, 0)
      val plotOther2Total =
          ObservedPlotSpeciesTotalsRow(
              observationId, plotId, null, "Other 2", Other, 0, 1, 0, null, 0, 0)
      val plotUnknownTotal =
          ObservedPlotSpeciesTotalsRow(
              observationId, plotId, null, null, Unknown, 1, 0, 0, null, 0, 0)

      helper.assertTotals(
          setOf(
              plotSpecies1Totals,
              plotSpecies2Totals,
              plotOther1Total,
              plotOther2Total,
              plotUnknownTotal,
          ),
          "Totals after observation")
    }

    @Test
    fun `creates new observation, plot and biomass details for monitoring observations`() {
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

      val observedTime = Instant.ofEpochSecond(1)
      clock.instant = Instant.ofEpochSecond(123)

      val date = LocalDate.ofInstant(observedTime, TimeZones.UTC)

      val (observationId, plotId) =
          service.completeAdHocObservation(
              biomassDetails = model,
              notes = "Notes",
              observedTime = observedTime,
              observationType = ObservationType.BiomassMeasurements,
              plantingSiteId = plantingSiteId,
              swCorner = point(1),
          )

      assertEquals(
          ObservationsRow(
              completedTime = clock.instant,
              createdTime = clock.instant,
              endDate = date,
              id = observationId,
              isAdHoc = true,
              observationTypeId = ObservationType.BiomassMeasurements,
              plantingSiteId = plantingSiteId,
              plantingSiteHistoryId = inserted.plantingSiteHistoryId,
              startDate = date,
              stateId = ObservationState.Completed,
          ),
          observationsDao.fetchOneById(observationId),
          "Observation row")

      val plotBoundary = Turtle(point(1)).makePolygon { square(MONITORING_PLOT_SIZE_INT) }

      val actual = monitoringPlotsDao.fetchOneById(plotId)
      assertEquals(
          MonitoringPlotsRow(
              createdBy = user.userId,
              createdTime = clock.instant,
              id = plotId,
              isAdHoc = true,
              isAvailable = false,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              organizationId = inserted.organizationId,
              plantingSiteId = plantingSiteId,
              plotNumber = 1,
              sizeMeters = MONITORING_PLOT_SIZE_INT,
          ),
          actual?.copy(boundary = null),
          "Ad-hoc plot row")
      assertGeometryEquals(plotBoundary, actual?.boundary, "Ad-hoc plot boundary")

      val latestPlotHistoryId =
          monitoringPlotHistoriesDao.fetchByMonitoringPlotId(plotId).maxOf { it.id!! }

      assertTableEquals(
          ObservationPlotsRecord(
              observationId = observationId,
              monitoringPlotId = plotId,
              claimedBy = user.userId,
              claimedTime = clock.instant,
              completedBy = user.userId,
              completedTime = clock.instant,
              createdBy = user.userId,
              createdTime = clock.instant,
              isPermanent = false,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              observedTime = observedTime,
              notes = "Notes",
              statusId = ObservationPlotStatus.Completed,
              monitoringPlotHistoryId = latestPlotHistoryId,
          ),
          "Observation plot record")

      // Detailed biomass row and tables are covered by ObservationStoreTest
      assertTableEquals(
          ObservationBiomassDetailsRecord(
              observationId = observationId,
              monitoringPlotId = plotId,
              description = "Basic biomass details",
              forestTypeId = BiomassForestType.Terrestrial,
              herbaceousCoverPercent = 0,
              smallTreesCountLow = 0,
              smallTreesCountHigh = 0,
              soilAssessment = "Basic soil assessment",
          ),
          "Biomass details table")
    }

    @Test
    fun `throws exception if no permission`() {
      deleteOrganizationUser()

      clock.instant = Instant.ofEpochSecond(500)

      assertThrows<EntityNotFoundException> {
        service.completeAdHocObservation(
            observedTime = clock.instant.minusSeconds(1),
            observationType = ObservationType.Monitoring,
            plantingSiteId = plantingSiteId,
            swCorner = point(1),
        )
      }
    }

    @Test
    fun `throws exception if no observed time is in the future outside of tolerance`() {
      assertThrows<IllegalArgumentException> {
        service.completeAdHocObservation(
            observedTime = clock.instant.plusSeconds(CLOCK_TOLERANCE_SECONDS + 1),
            observationType = ObservationType.BiomassMeasurements,
            plantingSiteId = plantingSiteId,
            swCorner = point(1),
        )
      }
    }

    @Test
    fun `throws exception if no biomass details was provided for Biomass measurements`() {
      assertThrows<IllegalArgumentException> {
        service.completeAdHocObservation(
            biomassDetails = null,
            observedTime = clock.instant,
            observationType = ObservationType.BiomassMeasurements,
            plantingSiteId = plantingSiteId,
            swCorner = point(1),
        )
      }
    }

    @Test
    fun `throws exception if plants were provided for Biomass measurements`() {
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

      assertThrows<IllegalArgumentException> {
        service.completeAdHocObservation(
            biomassDetails = model,
            observedTime = clock.instant,
            observationType = ObservationType.BiomassMeasurements,
            plantingSiteId = plantingSiteId,
            plants =
                setOf(
                    RecordedPlantsRow(
                        certaintyId = Other,
                        gpsCoordinates = point(1),
                        speciesName = "Other 1",
                        statusId = RecordedPlantStatus.Existing,
                    ),
                ),
            swCorner = point(1),
        )
      }
    }
  }

  @Nested
  inner class OnPlantingSiteMapEditedEvent {
    private val plantingSite: ExistingPlantingSiteModel by lazy {
      plantingSiteStore.fetchSiteById(inserted.plantingSiteId, PlantingSiteDepth.Plot)
    }

    @Test
    fun `abandons in-progress observations in edited zones`() {
      insertPlantingZone(numPermanentPlots = 1, numTemporaryPlots = 1, width = 2, height = 7)
      insertPlantingSubzone()
      val plotInEditedZone = insertMonitoringPlot(permanentCluster = 1)
      insertPlantingZone()
      insertPlantingSubzone()
      val plotInNonEditedZone = insertMonitoringPlot(permanentCluster = 1)

      val completedObservation = insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(
          monitoringPlotId = plotInEditedZone, isPermanent = true, completedBy = user.userId)
      insertObservationPlot(
          monitoringPlotId = plotInNonEditedZone, isPermanent = true, completedBy = user.userId)
      val activeObservationWithCompletedPlotInEditedZone = insertObservation()
      insertObservationPlot(
          monitoringPlotId = plotInEditedZone, isPermanent = true, completedBy = user.userId)
      insertObservationPlot(monitoringPlotId = plotInNonEditedZone, isPermanent = true)
      val activeObservationWithCompletedPlotInNonEditedZone = insertObservation()
      insertObservationPlot(monitoringPlotId = plotInEditedZone, isPermanent = true)
      insertObservationPlot(
          monitoringPlotId = plotInNonEditedZone, isPermanent = true, completedBy = user.userId)
      // Active observation with no completed plots; should be deleted
      insertObservation()
      insertObservationPlot(monitoringPlotId = plotInEditedZone, isPermanent = true)
      insertObservationPlot(monitoringPlotId = plotInNonEditedZone, isPermanent = true)

      val event =
          PlantingSiteMapEditedEvent(
              plantingSite,
              PlantingSiteEdit(
                  areaHaDifference = BigDecimal.ONE,
                  desiredModel = plantingSite,
                  existingModel = plantingSite,
                  plantingZoneEdits =
                      listOf(
                          PlantingZoneEdit.Update(
                              addedRegion = rectangle(0),
                              areaHaDifference = BigDecimal.ONE,
                              desiredModel = plantingSite.plantingZones[0],
                              existingModel = plantingSite.plantingZones[0],
                              monitoringPlotEdits = emptyList(),
                              plantingSubzoneEdits = emptyList(),
                              removedRegion = rectangle(0)))),
              ReplacementResult(emptySet(), emptySet()))

      service.on(event)

      assertEquals(
          mapOf(
              completedObservation to ObservationState.Completed,
              activeObservationWithCompletedPlotInEditedZone to ObservationState.InProgress,
              activeObservationWithCompletedPlotInNonEditedZone to ObservationState.Abandoned,
          ),
          observationsDao.findAll().associate { it.id!! to it.stateId!! },
          "Observation states")
    }
  }

  /**
   * Inserts a permanent monitoring plot with a given cluster number. By default, the clusters are
   * stacked northward, that is, cluster 1 is at y=0, cluster 2 is at y=1, and cluster 3 is at y=2.
   *
   * Optionally also includes the plot in the most-recently-inserted observation.
   */
  private fun insertPermanentPlot(
      permanentCluster: Int,
      x: Int = 0,
      y: Int = (permanentCluster - 1) * 2,
      isPermanent: Boolean = false,
      insertObservationPlots: Boolean = isPermanent,
  ): MonitoringPlotId {
    val plotId = insertMonitoringPlot(permanentCluster = permanentCluster, x = x, y = y)

    if (insertObservationPlots) {
      insertObservationPlot(isPermanent = isPermanent)
    }

    return plotId
  }
}
