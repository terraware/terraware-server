package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Known
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Other
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Unknown
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.embeddables.pojos.ObservationPlotId
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationMediaFilesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationsRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.file.mux.MuxStreamModel
import com.terraformation.backend.i18n.TimeZones
import com.terraformation.backend.onePixelPng
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.db.BiomassStore
import com.terraformation.backend.tracking.db.InvalidObservationEndDateException
import com.terraformation.backend.tracking.db.InvalidObservationStartDateException
import com.terraformation.backend.tracking.db.ObservationAlreadyStartedException
import com.terraformation.backend.tracking.db.ObservationHasNoSubstrataException
import com.terraformation.backend.tracking.db.ObservationLocker
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import com.terraformation.backend.tracking.db.ObservationPlotNotFoundException
import com.terraformation.backend.tracking.db.ObservationRescheduleStateException
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.ObservationTestHelper
import com.terraformation.backend.tracking.db.PlantingSiteNotDetailedException
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.db.PlotAlreadyCompletedException
import com.terraformation.backend.tracking.db.PlotNotCompletedException
import com.terraformation.backend.tracking.db.PlotNotInObservationException
import com.terraformation.backend.tracking.db.PlotSizeNotReplaceableException
import com.terraformation.backend.tracking.db.ScheduleObservationWithoutPlantsException
import com.terraformation.backend.tracking.db.SpeciesInWrongOrganizationException
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.StratumEdit
import com.terraformation.backend.tracking.event.ObservationMediaFileDeletedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileEditedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileEditedEventValues
import com.terraformation.backend.tracking.event.ObservationMediaFileUploadedEvent
import com.terraformation.backend.tracking.event.ObservationNotStartedEvent
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
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
  private val muxService: MuxService = mockk()
  private val observationLocker: ObservationLocker by lazy { ObservationLocker(dslContext) }
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val fileService: FileService by lazy {
    FileService(dslContext, clock, eventPublisher, filesDao, fileStore)
  }
  private val thumbnailService: ThumbnailService = mockk()
  private val biomassStore: BiomassStore by lazy {
    BiomassStore(dslContext, eventPublisher, observationLocker, parentStore)
  }
  private val observationStore: ObservationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        eventPublisher,
        observationLocker,
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        observationRequestedSubstrataDao,
        parentStore,
    )
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
        eventPublisher,
        strataDao,
        substrataDao,
    )
  }

  private val service: ObservationService by lazy {
    ObservationService(
        biomassStore,
        clock,
        dslContext,
        eventPublisher,
        fileService,
        monitoringPlotsDao,
        muxService,
        observationMediaFilesDao,
        observationLocker,
        observationStore,
        plantingSiteStore,
        parentStore,
        SystemUser(usersDao),
        thumbnailService,
    )
  }
  private val helper: ObservationTestHelper by lazy {
    ObservationTestHelper(this, observationStore, user.userId)
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
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
    fun `assigns correct plots to strata`() {
      // Given a planting site with this structure:
      //
      // Stratum 1 (2 permanent, 3 temporary)
      //   Substratum 1
      //     Plot A - permanent index 1
      //     Plot B - permanent index 3
      //     Plot C
      //     Plot D
      //   Substratum 2
      //     Plot E - permanent index 2
      //     Plot F
      //     Plot G
      // Stratum 2 (2 permanent, 2 temporary)
      //   Substratum 3
      //     Plot H - permanent 1
      //
      // When we start an observation with substratum 1 requested, we should get:
      // - One permanent plot in substratum 1.
      // - Two temporary plots in substratum 1. The stratum is configured for 3 temporary plots. 2
      //   of them are spread evenly across the 2 substrata, and the remaining one is placed in the
      //   substratum with the fewest permanent plots that could potentially be included in the
      //   observation.
      //   Since permanent indexes 1 and 2 are spread evenly across substrata, preference is given
      //   to requested substrata, meaning substratum 1 is picked.
      // - Nothing from stratum 2 because it was not requested.

      insertFacility(type = FacilityType.Nursery)

      insertStratum(x = 0, width = 4, height = 1, numPermanentPlots = 2, numTemporaryPlots = 3)
      val substratum1Boundary =
          rectangle(width = 4 * MONITORING_PLOT_SIZE, height = MONITORING_PLOT_SIZE)
      val substratumId1 = insertSubstratum(boundary = substratum1Boundary)
      insertPermanentPlot(1)
      insertPermanentPlot(3, x = 1, y = 0)
      insertMonitoringPlot(x = 2, y = 0)
      insertMonitoringPlot(x = 3, y = 0)

      insertSubstratum(x = 4, width = 3, height = 1)
      insertPermanentPlot(2, x = 4, y = 0)
      insertMonitoringPlot(x = 5, y = 0)
      insertMonitoringPlot(x = 6, y = 0)

      insertStratum(x = 7, width = 3, height = 1, numPermanentPlots = 2, numTemporaryPlots = 2)
      insertSubstratum(x = 7, width = 3, height = 1)
      insertPermanentPlot(1, x = 7, y = 0)

      val observationId = insertObservation(state = ObservationState.Upcoming)
      insertObservationRequestedSubstratum(substratumId = substratumId1)

      service.startObservation(observationId)

      val observationPlots = observationPlotsDao.findAll()
      val monitoringPlots = monitoringPlotsDao.findAll().associateBy { it.id }

      assertEquals(1, observationPlots.count { it.isPermanent!! }, "Number of permanent plots")
      assertEquals(2, observationPlots.count { !it.isPermanent!! }, "Number of temporary plots")

      observationPlots.forEach { observationPlot ->
        val plotBoundary = monitoringPlots[observationPlot.monitoringPlotId]!!.boundary!!
        if (plotBoundary.intersection(substratum1Boundary).area < plotBoundary.area * 0.99999) {
          fail(
              "Plot boundary $plotBoundary does not fall within substratum boundary $substratum1Boundary"
          )
        }
      }

      assertEquals(
          ObservationState.InProgress,
          observationsDao.fetchOneById(observationId)!!.stateId,
          "Observation state",
      )

      eventPublisher.assertEventPublished(
          ObservationStartedEvent(observationStore.fetchObservationById(observationId))
      )
    }

    @Test
    fun `creates new plots in correct strata`() {
      // Given a planting site with this structure:
      //
      // Stratum 1 (2 permanent, 3 temporary)
      //   Substratum 1
      //   Substratum 2
      // Stratum 2 (2 permanent, 2 temporary)
      //   Substratum 3
      //
      // An observation is started with substratum 1 requested.
      //
      // In stratum 1, the service should create two permanent plots. The plots might both end up in
      // substratum 1 or substratum 2, or there might be one plot in each substratum, depending on
      // the random number generator.
      //
      // The placement of the permanent plots will also determine how many temporary plots are
      // created and included in the observation.
      //
      // If both permanent plots are in substratum 1:
      // - They should both be included in the observation.
      // - We should get one temporary plot in substratum 1. The stratum is configured for 3
      //   temporary plots. 2 of them are spread evenly across the 2 substrata, and the remaining
      //   one is placed in the substratum with the fewest permanent plots, which is substratum 2,
      //   but substratum 2's plots are excluded because it wasn't requested.
      // If one permanent plot is in substratum 1 and the other in substratum 2:
      // - The plot in substratum 1 should be included in the observation, but not the one in
      //   substratum 2, because we only include permanent plots in requested substrata.
      // - We should get two temporary plots in substratum 1. Two of stratum 1's temporary plots are
      //   spread evenly across the two substrata, and the remaining plot is placed in the
      //   substratum with the fewest permanent plots, but in this case the substrata have the same
      //   number. As a tiebreaker, requested substrata are preferred over unrequested ones, which
      //   means we should choose substratum 1.
      // If both permanent plots are in substratum 2:
      // - Neither permanent plot should be included in the observation.
      // - We should get two temporary plots in substratum 1. Two of stratum 1's temporary plots are
      //   spread evenly across the two substrata, and the remaining plot is placed in the
      //   substratum with the fewest temporary plots, which is substratum 1 in this case.
      //
      // In stratum 2, the service should create two permanent plots, but neither of them should be
      // included in the observation since they all lie in an unrequested substratum.

      plantingSiteId = insertPlantingSite(x = 0, width = 14, gridOrigin = point(1))
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      insertStratum(x = 0, width = 6, height = 2, numPermanentPlots = 2, numTemporaryPlots = 3)
      val substratum1Boundary =
          rectangle(width = 3 * MONITORING_PLOT_SIZE, height = 2 * MONITORING_PLOT_SIZE)
      val substratum1Id = insertSubstratum(boundary = substratum1Boundary)

      val substratum2Id = insertSubstratum(x = 3, width = 3, height = 2)

      insertStratum(x = 6, width = 8, height = 2, numPermanentPlots = 2, numTemporaryPlots = 2)
      insertSubstratum(x = 6, width = 8, height = 2)

      val observationId = insertObservation(state = ObservationState.Upcoming)
      insertObservationRequestedSubstratum(substratumId = substratum1Id)

      // Make sure we actually get all the possible plot configurations.
      var got0PermanentPlotsInSubstratum1 = false
      var got1PermanentPlotInSubstratum1 = false
      var got2PermanentPlotsInSubstratum1 = false
      val maxTestRuns = 100
      var testRuns = 0

      while (
          testRuns++ < maxTestRuns &&
              !(got0PermanentPlotsInSubstratum1 &&
                  got1PermanentPlotInSubstratum1 &&
                  got2PermanentPlotsInSubstratum1)
      ) {
        dslContext.savepoint("start").execute()

        service.startObservation(observationId)

        val observationPlots = observationPlotsDao.findAll()
        val monitoringPlots = monitoringPlotsDao.findAll()
        val monitoringPlotsById = monitoringPlots.associateBy { it.id }

        // All selected plots should be in substratum 1; make sure their boundaries agree with their
        // substratum numbers.
        observationPlots.forEach { observationPlot ->
          val plot = monitoringPlotsById[observationPlot.monitoringPlotId]!!
          val plotBoundary = plot.boundary!!

          assertEquals(
              substratum1Id,
              plot.substratumId,
              "Substratum ID for plot ${plot.id}",
          )

          if (plotBoundary.intersection(substratum1Boundary).area < plotBoundary.area * 0.99999) {
            fail(
                "Plot boundary $plotBoundary does not fall within substratum boundary $substratum1Boundary"
            )
          }
        }

        val numPermanentPlotsBySubstratum: Map<SubstratumId, Int> =
            monitoringPlots
                .filter { it.permanentIndex != null }
                .groupBy { it.substratumId!! }
                .mapValues { it.value.size }
        val numPermanentPlotsInSubstratum1 = numPermanentPlotsBySubstratum[substratum1Id] ?: 0
        val numPermanentPlotsInSubstratum2 = numPermanentPlotsBySubstratum[substratum2Id] ?: 0

        assertEquals(
            4,
            monitoringPlots.count { it.permanentIndex != null },
            "Total number of permanent plots created",
        )
        assertEquals(
            2,
            numPermanentPlotsInSubstratum1 + numPermanentPlotsInSubstratum2,
            "Number of permanent plots created in stratum 1",
        )

        val expectedTemporaryPlots =
            when (numPermanentPlotsInSubstratum1) {
              2 -> 1
              1 -> 2
              0 -> 2
              else ->
                  fail(
                      "Expected 0, 1, or 2 permanent plots in substratum $substratum1Id, but " +
                          "got $numPermanentPlotsInSubstratum1"
                  )
            }

        assertEquals(
            expectedTemporaryPlots,
            observationPlots.count { !it.isPermanent!! },
            "Number of temporary plots in observation",
        )

        assertEquals(
            ObservationState.InProgress,
            observationsDao.fetchOneById(observationId)!!.stateId,
            "Observation state",
        )

        eventPublisher.assertEventPublished(
            ObservationStartedEvent(observationStore.fetchObservationById(observationId))
        )

        when (numPermanentPlotsInSubstratum1) {
          0 -> got0PermanentPlotsInSubstratum1 = true
          1 -> got1PermanentPlotInSubstratum1 = true
          2 -> got2PermanentPlotsInSubstratum1 = true
        }

        eventPublisher.clear()
        dslContext.rollback().toSavepoint("start").execute()
      }

      assertNotEquals(
          maxTestRuns,
          testRuns,
          "Number of test runs without seeing all possible permanent plot counts",
      )
    }

    @Test
    fun `only includes plots in requested substrata`() {
      // Given a planting site with this structure:
      //
      // +------------------------------------------------------------------------------------+
      // |                                      Stratum 1                                     |
      // +----------------+----------------+----------------+----------------+----------------+
      // |  Substratum 1  |  Substratum 2  |  Substratum 3  |  Substratum 4  |  Substratum 5  |
      // +----------------+----------------+----------------+----------------+----------------+
      //
      // Stratum 1: 8 permanent plots, 6 temporary plots
      //   Substratum 2: 2 permanent plots
      //   Substratum 3: 2 permanent plots
      //   Substratum 5: 4 permanent plots
      //
      // When the observation is requested for substrata 1 and 2, the observation should include
      // two temporary plots in substratum 1 and one temporary plot in substratum 2:
      //
      // - Five temporary plots are allocated evenly across the substrata
      // - The remaining temporary plot is placed in the substratum with the fewest number of
      //   permanent plots (this is substrata 1 and 4, with 0 each) with priority to requested
      //   substrata (which means substratum 1 gets it, for a total of two plots including the one
      //   from the "evenly allocated across substrata" step).
      // - Substrata that are not requested are not included in the observation, meaning we only
      //   include the plots in substrata 1 and 2.

      plantingSiteId = insertPlantingSite(x = 0, width = 15, gridOrigin = point(1))
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      insertStratum(
          x = 0,
          width = 15,
          height = 2,
          numPermanentPlots = 8,
          numTemporaryPlots = 6,
      )
      val substratumIds =
          listOf(SubstratumId(0)) +
              (0..4).map { index -> insertSubstratum(x = 3 * index, width = 3) }

      // Pre-existing permanent plots in substrata 2, 3, and 5
      listOf(5 to 0, 5 to 1, 7 to 0, 7 to 1, 13 to 0, 13 to 1, 14 to 0, 14 to 1).forEachIndexed {
          index,
          (x, y) ->
        insertMonitoringPlot(
            x = x,
            y = y,
            substratumId = substratumIds[x / 3 + 1],
            permanentIndex = index + 1,
        )
      }

      val observationId = insertObservation(state = ObservationState.Upcoming)
      insertObservationRequestedSubstratum(substratumId = substratumIds[1])
      insertObservationRequestedSubstratum(substratumId = substratumIds[2])

      service.startObservation(observationId)

      val substratumIdsByMonitoringPlot =
          monitoringPlotsDao.findAll().associate { it.id to it.substratumId }
      val observationPlots = observationPlotsDao.findAll()

      val numTemporaryPlotsBySubstratum =
          observationPlots
              .filter { !it.isPermanent!! }
              .groupBy { substratumIdsByMonitoringPlot[it.monitoringPlotId] }
              .mapValues { it.value.size }
      assertEquals(
          mapOf(substratumIds[1] to 2, substratumIds[2] to 1),
          numTemporaryPlotsBySubstratum,
          "Number of temporary plots by substratum",
      )

      val numPermanentPlotsBySubstratum =
          observationPlots
              .filter { it.isPermanent!! }
              .groupBy { substratumIdsByMonitoringPlot[it.monitoringPlotId] }
              .mapValues { it.value.size }
      assertEquals(
          mapOf(substratumIds[2] to 2),
          numPermanentPlotsBySubstratum,
          "Number of permanent plots by substratum",
      )
    }

    @Test
    fun `sets planting site history ID`() {
      val boundary = rectangle(width = 4 * MONITORING_PLOT_SIZE, height = MONITORING_PLOT_SIZE)

      insertStratum(boundary = boundary, numPermanentPlots = 1, numTemporaryPlots = 1)
      insertSubstratum(boundary = boundary)

      val observationId = insertObservation(state = ObservationState.Upcoming)
      insertObservationRequestedSubstratum()

      val updatedSiteHistoryId = insertPlantingSiteHistory()
      insertStratumHistory()
      insertSubstratumHistory()

      service.startObservation(observationId)

      assertEquals(
          updatedSiteHistoryId,
          observationsDao.fetchOneById(observationId)?.plantingSiteHistoryId,
          "Planting site history ID",
      )
    }

    @Test
    fun `deletes observation and publishes event if planting site is too small`() {
      val boundary = rectangle(MONITORING_PLOT_SIZE)

      insertStratum(boundary = boundary, numPermanentPlots = 1, numTemporaryPlots = 1)
      insertSubstratum(boundary = boundary)

      val observationId = insertObservation(state = ObservationState.Upcoming)
      insertObservationRequestedSubstratum()

      service.startObservation(observationId)

      assertTableEmpty(OBSERVATIONS)

      eventPublisher.assertEventPublished(ObservationNotStartedEvent(observationId, plantingSiteId))
    }

    @Test
    fun `throws exception if observation already started`() {
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot()
      val observationId = insertObservation(state = ObservationState.InProgress)

      assertThrows<ObservationAlreadyStartedException> { service.startObservation(observationId) }
    }

    @Test
    fun `throws exception if observation already has plots assigned`() {
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot()
      val observationId = insertObservation(state = ObservationState.Upcoming)

      insertObservationPlot()

      assertThrows<ObservationAlreadyStartedException> { service.startObservation(observationId) }
    }

    @Test
    fun `throws exception if observation has no requested substrata`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)

      assertThrows<ObservationHasNoSubstrataException> { service.startObservation(observationId) }
    }

    @Test
    fun `throws exception if no permission to manage observation`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)

      deleteUserGlobalRole(role = GlobalRole.SuperAdmin)

      assertThrows<AccessDeniedException> { service.startObservation(observationId) }
    }
  }

  @Nested
  inner class MediaFiles {
    private lateinit var observationId: ObservationId
    private lateinit var plotId: MonitoringPlotId

    private val metadata = FileMetadata.of(MediaType.IMAGE_JPEG_VALUE, "filename", 1L, point(1))

    @BeforeEach
    fun setUp() {
      insertStratum()
      insertSubstratum()
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
            service.storeMediaFile(
                observationId = observationId,
                monitoringPlotId = plotId,
                position = ObservationPlotPosition.NortheastCorner,
                data = content.inputStream(),
                metadata = metadata,
                caption = null,
                isOriginal = true,
            )
      }

      @Test
      fun `returns photo data`() {
        every { thumbnailService.readFile(fileId) } returns
            SizedInputStream(content.inputStream(), content.size.toLong())

        val inputStream = service.readPhoto(observationId, plotId, fileId)
        assertArrayEquals(content, inputStream.readAllBytes())
      }

      @Test
      fun `returns thumbnail data`() {
        val maxWidth = 40
        val maxHeight = 30
        val thumbnailContent = byteArrayOf(9, 8, 7)

        every { thumbnailService.readFile(fileId, maxWidth, maxHeight) } returns
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
    inner class GetMuxStreamInfo {
      @Test
      fun `returns stream info`() {
        val fileId = insertFile()
        insertObservationMediaFile()

        val streamModel = MuxStreamModel(fileId, "playback", "token")
        every { muxService.getMuxStream(fileId) } returns streamModel

        assertEquals(streamModel, service.getMuxStreamInfo(observationId, plotId, fileId))
      }

      @Test
      fun `throws exception if file does not exist on observation`() {
        val fileId = insertFile()
        insertObservationMediaFile()
        val otherObservationId = insertObservation()

        assertThrows<FileNotFoundException> {
          service.getMuxStreamInfo(otherObservationId, plotId, fileId)
        }
      }

      @Test
      fun `throws exception if no permission to read observation`() {
        val fileId = insertFile()
        insertObservationMediaFile()

        deleteOrganizationUser()

        assertThrows<ObservationNotFoundException> {
          service.getMuxStreamInfo(observationId, plotId, fileId)
        }
      }
    }

    @Nested
    inner class StoreMediaFile {
      @Test
      fun `associates media with observation and plot`() {
        val fileId1 =
            service.storeMediaFile(
                observationId = observationId,
                monitoringPlotId = plotId,
                position = ObservationPlotPosition.NortheastCorner,
                data = byteArrayOf(1).inputStream(),
                metadata = metadata,
                caption = "caption",
                isOriginal = true,
            )

        val fileId2 =
            service.storeMediaFile(
                observationId = observationId,
                monitoringPlotId = plotId,
                position = null,
                data = byteArrayOf(1).inputStream(),
                metadata = metadata.copy(geolocation = null),
                caption = null,
                isOriginal = false,
                type = ObservationMediaType.Soil,
            )

        fileStore.assertFileExists(filesDao.fetchOneById(fileId1)!!.storageUrl!!)
        fileStore.assertFileExists(filesDao.fetchOneById(fileId2)!!.storageUrl!!)

        assertTableEquals(
            listOf(
                ObservationMediaFilesRecord(
                    fileId = fileId1,
                    observationId = observationId,
                    monitoringPlotId = plotId,
                    positionId = ObservationPlotPosition.NortheastCorner,
                    typeId = ObservationMediaType.Plot,
                    caption = "caption",
                    isOriginal = true,
                ),
                ObservationMediaFilesRecord(
                    fileId = fileId2,
                    observationId = observationId,
                    monitoringPlotId = plotId,
                    typeId = ObservationMediaType.Soil,
                    isOriginal = false,
                ),
            )
        )

        val filesRecords = dslContext.fetch(FILES)
        assertGeometryEquals(
            point(1),
            filesRecords.single { it.id == fileId1 }.geolocation,
            "File 1",
        )
        assertNull(filesRecords.single { it.id == fileId2 }.geolocation, "File 2 geolocation")
      }

      @Test
      fun `publishes upload event`() {
        val fileId =
            service.storeMediaFile(
                caption = "caption",
                data = byteArrayOf(1).inputStream(),
                isOriginal = true,
                metadata = metadata,
                monitoringPlotId = plotId,
                observationId = observationId,
                position = ObservationPlotPosition.NortheastCorner,
            )

        eventPublisher.assertEventPublished(
            ObservationMediaFileUploadedEvent(
                caption = "caption",
                contentType = metadata.contentType,
                geolocation = metadata.geolocation,
                fileId = fileId,
                isOriginal = true,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                position = ObservationPlotPosition.NortheastCorner,
                monitoringPlotId = plotId,
                type = ObservationMediaType.Plot,
            )
        )
      }

      @Test
      fun `throws exception for missing photo position for Quadrat photos`() {
        assertThrows<IllegalArgumentException> {
          service.storeMediaFile(
              observationId = observationId,
              monitoringPlotId = plotId,
              position = null,
              data = byteArrayOf(1).inputStream(),
              metadata = metadata,
              caption = null,
              isOriginal = true,
              type = ObservationMediaType.Quadrat,
          )
        }
      }

      @Test
      fun `throws exception for providing photo position for Soil photos`() {
        assertThrows<IllegalArgumentException> {
          service.storeMediaFile(
              observationId = observationId,
              monitoringPlotId = plotId,
              position = ObservationPlotPosition.SoutheastCorner,
              data = byteArrayOf(1).inputStream(),
              metadata = metadata,
              caption = null,
              isOriginal = true,
              type = ObservationMediaType.Soil,
          )
        }
      }

      @Test
      fun `throws exception if no permission to update observation`() {
        deleteOrganizationUser()

        assertThrows<ObservationNotFoundException> {
          service.storeMediaFile(
              observationId = observationId,
              monitoringPlotId = plotId,
              position = ObservationPlotPosition.NortheastCorner,
              data = byteArrayOf(1).inputStream(),
              metadata = metadata,
              caption = null,
              isOriginal = true,
          )
        }
      }
    }

    @Nested
    inner class DeleteMediaFile {
      @Test
      fun `deletes non-original photo`() {
        val fileId = storeMedia(ObservationMediaType.Soil, isOriginal = false)

        service.deleteMediaFile(observationId, plotId, fileId)

        assertTableEmpty(OBSERVATION_MEDIA_FILES)
        eventPublisher.assertEventsPublished(
            setOf(
                ObservationMediaFileDeletedEvent(
                    fileId,
                    plotId,
                    observationId,
                    organizationId,
                    plantingSiteId,
                ),
                FileReferenceDeletedEvent(fileId),
            )
        )
      }

      @Test
      fun `throws exception if deleting original photo`() {
        val fileId =
            storeMedia(
                ObservationMediaType.Plot,
                ObservationPlotPosition.NorthwestCorner,
                isOriginal = true,
            )

        assertThrows<AccessDeniedException> {
          service.deleteMediaFile(observationId, plotId, fileId)
        }
      }

      @Test
      fun `throws exception if no permission to update observation`() {
        val fileId = storeMedia(ObservationMediaType.Plot)

        deleteOrganizationUser()

        assertThrows<ObservationNotFoundException> {
          service.deleteMediaFile(observationId, plotId, fileId)
        }
      }

      private fun storeMedia(
          type: ObservationMediaType,
          position: ObservationPlotPosition? = null,
          isOriginal: Boolean = false,
      ): FileId {
        return service.storeMediaFile(
            observationId = observationId,
            monitoringPlotId = plotId,
            position = position,
            data = byteArrayOf(1).inputStream(),
            metadata = metadata,
            caption = "caption",
            isOriginal = isOriginal,
            type = type,
        )
      }
    }

    @Nested
    inner class UpdateMediaFile {
      @Test
      fun `updates editable properties`() {
        val fileId =
            service.storeMediaFile(
                observationId = observationId,
                monitoringPlotId = plotId,
                position = ObservationPlotPosition.NortheastCorner,
                data = byteArrayOf(1).inputStream(),
                metadata = metadata,
                caption = "old caption",
                isOriginal = true,
                type = ObservationMediaType.Quadrat,
            )

        clock.instant = Instant.ofEpochSecond(30)

        val expectedFilesRecord = dslContext.fetchSingle(FILES)
        expectedFilesRecord.modifiedTime = clock.instant

        service.updateMediaFile(observationId, plotId, fileId) {
          it.copy(
              caption = "new caption",
              positionId = ObservationPlotPosition.SouthwestCorner,
              typeId = ObservationMediaType.Plot,
          )
        }

        assertTableEquals(
            ObservationMediaFilesRecord(
                fileId,
                observationId,
                plotId,
                ObservationPlotPosition.NortheastCorner,
                ObservationMediaType.Quadrat,
                "new caption",
                true,
            )
        )

        assertTableEquals(expectedFilesRecord)

        eventPublisher.assertEventPublished(
            ObservationMediaFileEditedEvent(
                changedFrom = ObservationMediaFileEditedEventValues("old caption"),
                changedTo = ObservationMediaFileEditedEventValues("new caption"),
                fileId = fileId,
                monitoringPlotId = plotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
            )
        )
      }

      @Test
      fun `throws exception if no permission to update observation`() {
        val fileId =
            service.storeMediaFile(
                observationId = observationId,
                monitoringPlotId = plotId,
                position = ObservationPlotPosition.NortheastCorner,
                data = byteArrayOf(1).inputStream(),
                metadata = metadata,
                caption = "caption",
                isOriginal = true,
                type = ObservationMediaType.Quadrat,
            )

        deleteOrganizationUser()

        assertThrows<ObservationNotFoundException> {
          service.updateMediaFile(observationId, plotId, fileId) { it }
        }
      }
    }

    @Nested
    inner class OnPlantingSiteDeletion {
      @Test
      fun `only deletes media for planting site that is being deleted`() {
        val fileId =
            service.storeMediaFile(
                observationId = observationId,
                monitoringPlotId = plotId,
                position = ObservationPlotPosition.NortheastCorner,
                data = onePixelPng.inputStream(),
                metadata = metadata,
                caption = null,
                isOriginal = true,
            )

        insertPlantingSite()
        insertStratum()
        insertSubstratum()
        insertMonitoringPlot()
        insertObservation()
        insertObservationPlot()

        val otherSiteFileId =
            service.storeMediaFile(
                observationId = inserted.observationId,
                monitoringPlotId = inserted.monitoringPlotId,
                position = ObservationPlotPosition.SouthwestCorner,
                data = onePixelPng.inputStream(),
                metadata = metadata,
                caption = null,
                isOriginal = true,
            )

        eventPublisher.clear()

        service.on(PlantingSiteDeletionStartedEvent(plantingSiteId))

        eventPublisher.assertExactEventsPublished(
            setOf(
                FileReferenceDeletedEvent(fileId),
                ObservationMediaFileDeletedEvent(
                    fileId,
                    plotId,
                    observationId,
                    organizationId,
                    plantingSiteId,
                ),
            )
        )

        assertEquals(
            listOf(otherSiteFileId),
            observationMediaFilesDao.findAll().map { it.fileId },
            "Observation media table should only have file from other observation",
        )
      }
    }
  }

  @Nested
  inner class MergeOtherSpecies {
    // Tests for the monitoring and biomass logic are in the ObservationStore and BiomassStore test
    // suites.

    @Test
    fun `throws exception if not in organization`() {
      val observationId = insertObservation()
      val speciesId = insertSpecies()

      deleteOrganizationUser()

      assertThrows<SpeciesNotFoundException> {
        service.mergeOtherSpecies(observationId, "Other", speciesId)
      }
    }

    @Test
    fun `throws exception if no permission to update species`() {
      val observationId = insertObservation()
      val speciesId = insertSpecies()

      dslContext
          .update(ORGANIZATION_USERS)
          .set(ORGANIZATION_USERS.ROLE_ID, Role.Contributor)
          .where(ORGANIZATION_USERS.USER_ID.eq(user.userId))
          .execute()

      assertThrows<AccessDeniedException> {
        service.mergeOtherSpecies(observationId, "Other", speciesId)
      }
    }

    @Test
    fun `throws exception if species is from a different organization`() {
      val observationId = insertObservation()
      insertOrganization()
      insertOrganizationUser(role = Role.Admin)
      val speciesId = insertSpecies()

      assertThrows<SpeciesInWrongOrganizationException> {
        service.mergeOtherSpecies(observationId, "Other", speciesId)
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
                plantingSiteId = plantingSiteId,
                startDate = startDate,
                endDate = endDate,
            )
        )
      }
    }

    @Test
    fun `throws exception scheduling an observation if start date is more than a year in the future`() {
      val startDate = LocalDate.EPOCH.plusYears(1).plusDays(1)
      val endDate = startDate.plusDays(1)

      assertThrows<InvalidObservationStartDateException> {
        service.scheduleObservation(
            newObservationModel(
                plantingSiteId = plantingSiteId,
                startDate = startDate,
                endDate = endDate,
            )
        )
      }
    }

    @Test
    fun `throws exception scheduling an observation if end date is on or before the start date`() {
      val startDate = LocalDate.EPOCH.plusDays(5)
      val endDate = startDate.minusDays(2)

      assertThrows<InvalidObservationEndDateException> {
        service.scheduleObservation(
            newObservationModel(
                plantingSiteId = plantingSiteId,
                startDate = startDate,
                endDate = endDate,
            )
        )
      }
    }

    @Test
    fun `throws exception scheduling an observation if end date is more than 2 months after the start date`() {
      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusMonths(2).plusDays(1)

      assertThrows<InvalidObservationEndDateException> {
        service.scheduleObservation(
            newObservationModel(
                plantingSiteId = plantingSiteId,
                startDate = startDate,
                endDate = endDate,
            )
        )
      }
    }

    @Test
    fun `throws exception scheduling an observation on a site with no plants in substrata`() {
      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      assertThrows<ScheduleObservationWithoutPlantsException> {
        service.scheduleObservation(
            newObservationModel(
                plantingSiteId = plantingSiteId,
                startDate = startDate,
                endDate = endDate,
            )
        )
      }
    }

    @Test
    fun `schedules a new observation for a site with plantings in substrata`() {
      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      helper.insertPlantedSite(numPermanentPlots = 2, numTemporaryPlots = 3)

      val observationId =
          service.scheduleObservation(
              newObservationModel(
                  plantingSiteId = inserted.plantingSiteId,
                  startDate = startDate,
                  endDate = endDate,
              )
          )

      assertNotNull(observationId)

      val createdObservation = observationStore.fetchObservationById(observationId)

      assertEquals(
          ObservationState.Upcoming,
          createdObservation.state,
          "State should show as Upcoming",
      )
      assertEquals(
          startDate,
          createdObservation.startDate,
          "Start date should match schedule input",
      )
      assertEquals(endDate, createdObservation.endDate, "End date should match schedule input")

      eventPublisher.assertExactEventsPublished(
          setOf(ObservationScheduledEvent(createdObservation))
      )
    }

    private fun newObservationModel(
        plantingSiteId: PlantingSiteId,
        startDate: LocalDate = LocalDate.EPOCH,
        endDate: LocalDate = LocalDate.EPOCH.plusDays(1),
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
            ]
    )
    fun `throws exception rescheduling an In-Progress or Overdue observation if there are observed plots`(
        stateName: String
    ) {
      insertPlantingSite()
      insertStratum(numPermanentPlots = 1, numTemporaryPlots = 1)
      insertSubstratum()
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
            ]
    )
    fun `reschedules an In-Progress or Overdue observation if there are no observed plots`(
        stateName: String
    ) {
      insertPlantingSite(x = 0)
      insertStratum(numPermanentPlots = 1, numTemporaryPlots = 1)
      insertSubstratum()
      insertMonitoringPlot()

      val observationId = insertObservation(state = ObservationState.valueOf(stateName))
      insertObservationPlot()
      val originalObservation = observationStore.fetchObservationById(observationId)

      val startDate = LocalDate.EPOCH
      val endDate = startDate.plusDays(1)

      service.rescheduleObservation(observationId, startDate, endDate)

      val updatedObservation = observationStore.fetchObservationById(observationId)
      assertEquals(
          ObservationState.Upcoming,
          updatedObservation.state,
          "State should show as Upcoming",
      )
      assertEquals(startDate, updatedObservation.startDate, "Start date should be updated")
      assertEquals(endDate, updatedObservation.endDate, "End date should be updated")
      assertTableEmpty(OBSERVATION_PLOTS, "Observation plots should be removed")

      eventPublisher.assertExactEventsPublished(
          setOf(ObservationRescheduledEvent(originalObservation, updatedObservation))
      )
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
          ObservationState.Upcoming,
          updatedObservation.state,
          "State should show as Upcoming",
      )
      assertEquals(startDate, updatedObservation.startDate, "Start date should be updated")
      assertEquals(endDate, updatedObservation.endDate, "End date should be updated")

      eventPublisher.assertExactEventsPublished(
          setOf(ObservationRescheduledEvent(originalObservation, updatedObservation))
      )
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
    fun `returns site with substratum plantings and no completed observations`() {
      helper.insertPlantedSite()

      assertEquals(
          listOf(inserted.plantingSiteId),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria),
      )
    }

    @Test
    fun `returns empty results with observations completed more recent than two weeks`() {
      helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH),
          state = ObservationState.Completed,
      )

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results with observations completed earlier than two weeks but with other observations scheduled`() {
      helper.insertPlantedSite()

      insertObservation(state = ObservationState.Upcoming)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(2 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns sites with observations completed earlier than two weeks or have substratum plantings`() {
      val plantingSiteIdWithCompletedObservation = helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(2 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      val anotherPlantingSiteIdWithCompletedObservation = helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(2 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      // planting site with a more recent completion
      helper.insertPlantedSite()
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH),
          state = ObservationState.Completed,
      )

      val plantingSiteIdWithPlantings = helper.insertPlantedSite()

      assertSetEquals(
          setOf(
              plantingSiteIdWithCompletedObservation,
              anotherPlantingSiteIdWithCompletedObservation,
              plantingSiteIdWithPlantings,
          ),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).toSet(),
      )
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
    fun `returns site with substratum plantings planted earlier than 4 weeks and no completed observations`() {
      helper.insertPlantedSite(plantingCreatedTime = Instant.EPOCH.minus(7 * 4, ChronoUnit.DAYS))
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      assertEquals(
          listOf(inserted.plantingSiteId),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria),
      )
    }

    @Test
    fun `returns empty results with observations completed more recent than six weeks`() {
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH),
          state = ObservationState.Completed,
      )

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results with observations completed earlier than six weeks but with other observations scheduled`() {
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      insertObservation(state = ObservationState.Upcoming)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns sites with observations completed earlier than six weeks or have substratum plantings`() {
      val plantingSiteIdWithCompletedObservation = helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      val anotherPlantingSiteIdWithCompletedObservation = helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      // planting site with a more recent completion
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH),
          state = ObservationState.Completed,
      )

      val plantingSiteIdWithPlantings =
          helper.insertPlantedSite(
              plantingCreatedTime = Instant.EPOCH.minus(4 * 7, ChronoUnit.DAYS)
          )
      insertPlantingSiteNotification(type = NotificationType.ScheduleObservation)

      assertSetEquals(
          setOf(
              plantingSiteIdWithCompletedObservation,
              anotherPlantingSiteIdWithCompletedObservation,
              plantingSiteIdWithPlantings,
          ),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).toSet(),
      )
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
    fun `returns site with substratum plantings planted earlier than 6 weeks and no completed observations`() {
      helper.insertPlantedSite(plantingCreatedTime = Instant.EPOCH.minus(7 * 6, ChronoUnit.DAYS))

      assertEquals(
          listOf(inserted.plantingSiteId),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria),
      )
    }

    @Test
    fun `returns empty results with observations completed more recent than eight weeks`() {
      helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH),
          state = ObservationState.Completed,
      )

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results with observations completed earlier than eight weeks but with other observations scheduled`() {
      helper.insertPlantedSite()

      insertObservation(state = ObservationState.Upcoming)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(8 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results if notification already sent`() {
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(8 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      assertEquals(
          emptyList<Any>(),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria),
      )
    }

    @Test
    fun `returns sites with observations completed earlier than eight weeks or have substratum plantings`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      val plantingSiteIdWithCompletedObservation = helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(8 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      val anotherPlantingSiteIdWithCompletedObservation = helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(8 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      // planting site with a more recent completion
      helper.insertPlantedSite()

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH),
          state = ObservationState.Completed,
      )

      val plantingSiteIdWithPlantings =
          helper.insertPlantedSite(
              plantingCreatedTime = Instant.EPOCH.minus(6 * 7, ChronoUnit.DAYS)
          )

      assertSetEquals(
          setOf(
              plantingSiteIdWithCompletedObservation,
              anotherPlantingSiteIdWithCompletedObservation,
              plantingSiteIdWithPlantings,
          ),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).toSet(),
      )
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
    fun `returns site with substratum plantings planted earlier than 14 weeks and no completed observations`() {
      helper.insertPlantedSite(plantingCreatedTime = Instant.EPOCH.minus(7 * 14, ChronoUnit.DAYS))
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      assertEquals(
          listOf(inserted.plantingSiteId),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria),
      )
    }

    @Test
    fun `returns empty results with observations completed more recent than sixteen weeks`() {
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH),
          state = ObservationState.Completed,
      )

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results with observations completed earlier than sixteen weeks but with other observations scheduled`() {
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      insertObservation(state = ObservationState.Upcoming)
      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      assert(service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).isEmpty())
    }

    @Test
    fun `returns empty results if notification already sent`() {
      helper.insertPlantedSite()
      insertPlantingSiteNotification(
          type = NotificationType.ObservationNotScheduledSupport,
          number = 2,
      )

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      assertEquals(
          emptyList<Any>(),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria),
      )
    }

    @Test
    fun `returns sites with observations completed earlier than sixteen weeks or have substratum plantings`() {
      val plantingSiteIdWithCompletedObservation = helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      val anotherPlantingSiteIdWithCompletedObservation = helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)),
          state = ObservationState.Completed,
      )

      // planting site with a more recent completion
      helper.insertPlantedSite()
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      insertObservation(
          ObservationsRow(completedTime = Instant.EPOCH),
          state = ObservationState.Completed,
      )

      val plantingSiteIdWithPlantings =
          helper.insertPlantedSite(
              plantingCreatedTime = Instant.EPOCH.minus(16 * 7, ChronoUnit.DAYS)
          )
      insertPlantingSiteNotification(type = NotificationType.ObservationNotScheduledSupport)

      assertSetEquals(
          setOf(
              plantingSiteIdWithCompletedObservation,
              anotherPlantingSiteIdWithCompletedObservation,
              plantingSiteIdWithPlantings,
          ),
          service.fetchNonNotifiedSitesToNotifySchedulingObservations(criteria).toSet(),
      )
    }
  }

  @Nested
  inner class ReplaceMonitoringPlot {
    private lateinit var observationId: ObservationId

    @BeforeEach
    fun setUp() {
      helper.insertPlantedSite(width = 2, height = 7, substratumCompletedTime = Instant.EPOCH)
      observationId = insertObservation()
      insertObservationRequestedSubstratum()
    }

    @Test
    fun `marks temporary plot as unavailable and clears its permanent index if duration is long-term`() {
      val plotId1 = insertPermanentPlot(1)
      insertPermanentPlot(2)
      insertPermanentPlot(3)
      insertObservationPlot(monitoringPlotId = plotId1)

      service.replaceMonitoringPlot(
          observationId,
          plotId1,
          "Meteor strike",
          ReplacementDuration.LongTerm,
      )

      val plots = monitoringPlotsDao.findAll().associateBy { it.id!! }

      assertNull(plots[plotId1]!!.permanentIndex, "Permanent index of plot that was replaced")

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
          observationId,
          monitoringPlotId,
          "Mudslide",
          ReplacementDuration.Temporary,
      )

      assertTrue(
          monitoringPlotsDao.fetchOneById(monitoringPlotId)!!.isAvailable!!,
          "Plot is available",
      )
    }

    @Test
    fun `can use previously-created plot as replacement`() {
      insertSubstratum(width = 2, height = 1, plantingCompletedTime = Instant.EPOCH)
      val monitoringPlotId = insertMonitoringPlot(x = 0)
      insertObservationPlot()
      val otherPlotId = insertMonitoringPlot(x = 1)

      val result =
          service.replaceMonitoringPlot(
              observationId,
              monitoringPlotId,
              "Mudslide",
              ReplacementDuration.Temporary,
          )

      assertEquals(ReplacementResult(setOf(otherPlotId), setOf(monitoringPlotId)), result)

      assertEquals(
          listOf(otherPlotId),
          observationPlotsDao.findAll().map { it.monitoringPlotId },
          "Observation should only have replacement plot",
      )
    }

    @Test
    fun `does not return an unavailable plot even if there are no other options`() {
      insertSubstratum(width = 2, height = 1, plantingCompletedTime = Instant.EPOCH)
      val monitoringPlotId = insertMonitoringPlot(x = 0)
      insertObservationPlot()
      insertMonitoringPlot(x = 1, isAvailable = false)

      val result =
          service.replaceMonitoringPlot(
              observationId,
              monitoringPlotId,
              "Mudslide",
              ReplacementDuration.Temporary,
          )

      assertEquals(ReplacementResult(emptySet(), setOf(monitoringPlotId)), result)

      assertTableEmpty(OBSERVATION_PLOTS, "Observation should not have any plots")
    }

    @Test
    fun `creates new temporary plot if needed`() {
      insertSubstratum(width = 2, height = 1, plantingCompletedTime = Instant.EPOCH)
      val monitoringPlotId = insertMonitoringPlot(x = 0)
      insertObservationPlot()

      val result =
          service.replaceMonitoringPlot(
              observationId,
              monitoringPlotId,
              "Mudslide",
              ReplacementDuration.Temporary,
          )

      val plots = monitoringPlotsDao.findAll()

      assertEquals(2, plots.size, "Number of monitoring plots after replacement")

      val otherPlotId = plots.first { it.id != monitoringPlotId }.id!!

      assertEquals(ReplacementResult(setOf(otherPlotId), setOf(monitoringPlotId)), result)

      assertEquals(
          listOf(otherPlotId),
          observationPlotsDao.findAll().map { it.monitoringPlotId },
          "Observation should only have replacement plot",
      )
    }

    @Test
    fun `replaces permanent plot if this is the first observation and there are no completed plots`() {
      insertStratum(numPermanentPlots = 1, width = 2, height = 4)
      insertSubstratum(width = 2, height = 4)
      insertObservationRequestedSubstratum()
      val plotId1 = insertPermanentPlot(1, isPermanent = true)
      val plotId2 = insertPermanentPlot(2)

      val result =
          service.replaceMonitoringPlot(
              observationId,
              plotId1,
              "why not",
              ReplacementDuration.Temporary,
          )

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = setOf(plotId2),
              removedMonitoringPlotIds = setOf(plotId1),
          ),
          result,
      )

      assertEquals(
          1,
          monitoringPlotsDao.fetchOneById(plotId2)!!.permanentIndex,
          "Should have moved second permanent plot to first place",
      )
      assertNotEquals(
          1,
          monitoringPlotsDao.fetchOneById(plotId1)!!.permanentIndex,
          "Plot $plotId1 index",
      )
    }

    @Test
    fun `removes permanent index if replacement plot is in an unrequested substratum`() {
      insertStratum(numPermanentPlots = 1, width = 1, height = 2)
      insertSubstratum(width = 1, height = 1)
      insertObservationRequestedSubstratum()
      val plotId1 = insertPermanentPlot(1, isPermanent = true)

      insertSubstratum(y = 1, width = 1, height = 1)
      val plotId2 = insertPermanentPlot(2)

      val result =
          service.replaceMonitoringPlot(
              observationId,
              plotId1,
              "why not",
              ReplacementDuration.Temporary,
          )

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = emptySet(),
              removedMonitoringPlotIds = setOf(plotId1),
          ),
          result,
      )

      assertEquals(
          1,
          monitoringPlotsDao.fetchOneById(plotId2)!!.permanentIndex,
          "Should have moved second permanent plot to first place",
      )
      assertNotEquals(
          1,
          monitoringPlotsDao.fetchOneById(plotId1)!!.permanentIndex,
          "Plot $plotId1 index",
      )
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
              observationId,
              plotId1,
              "why not",
              ReplacementDuration.LongTerm,
          )

      assertSetEquals(setOf(plotId1), result.removedMonitoringPlotIds, "Removed plot IDs")
      assertEquals(1, result.addedMonitoringPlotIds.size, "Number of plot IDs added")

      val plots = monitoringPlotsDao.findAll().associateBy { it.id!! }

      assertNull(plots[plotId1]!!.permanentIndex, "Permanent index of plot that was replaced")

      assertEquals(
          1,
          plots.values.count { it.permanentIndex == 1 },
          "Number of plots with permanent index 1",
      )

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
          completedTime = Instant.EPOCH,
      )

      val result =
          service.replaceMonitoringPlot(
              observationId,
              plotId1,
              "forest fire",
              ReplacementDuration.LongTerm,
          )

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = emptySet(),
              removedMonitoringPlotIds = setOf(plotId1),
          ),
          result,
      )

      assertEquals(
          true,
          monitoringPlotsDao.fetchOneById(plotId1)!!.isAvailable,
          "Monitoring plot should remain available",
      )
    }

    @Test
    fun `removes permanent plot but keeps it available if this is not the first observation`() {
      observationsDao.update(
          observationsDao
              .fetchOneById(inserted.observationId)!!
              .copy(completedTime = Instant.EPOCH, stateId = ObservationState.Completed)
      )

      val newObservationId = insertObservation()

      val plotId1 = insertPermanentPlot(1, isPermanent = true)

      val result =
          service.replaceMonitoringPlot(
              newObservationId,
              plotId1,
              "forest fire",
              ReplacementDuration.LongTerm,
          )

      assertEquals(
          ReplacementResult(
              addedMonitoringPlotIds = emptySet(),
              removedMonitoringPlotIds = setOf(plotId1),
          ),
          result,
      )

      assertEquals(
          true,
          monitoringPlotsDao.fetchOneById(plotId1)!!.isAvailable,
          "Monitoring plot should remain available",
      )
    }

    @Test
    fun `publishes event`() {
      val monitoringPlotId = insertMonitoringPlot()
      insertObservationPlot()

      service.replaceMonitoringPlot(
          observationId,
          monitoringPlotId,
          "justification",
          ReplacementDuration.Temporary,
      )

      // Default values from insertObservation()
      val observation =
          ExistingObservationModel(
              endDate = LocalDate.of(2023, 1, 31),
              id = observationId,
              isAdHoc = false,
              observationType = ObservationType.Monitoring,
              plantingSiteHistoryId = inserted.plantingSiteHistoryId,
              plantingSiteId = inserted.plantingSiteId,
              requestedSubstratumIds = setOf(inserted.substratumId),
              startDate = LocalDate.of(2023, 1, 1),
              state = ObservationState.InProgress,
          )

      eventPublisher.assertEventPublished(
          ObservationPlotReplacedEvent(
              duration = ReplacementDuration.Temporary,
              justification = "justification",
              observation = observation,
              monitoringPlotId = monitoringPlotId,
          )
      )
    }

    @Test
    fun `throws exception if plot is already completed`() {
      val monitoringPlotId = insertMonitoringPlot()
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          completedBy = user.userId,
          completedTime = Instant.EPOCH,
      )

      assertThrows<PlotAlreadyCompletedException> {
        service.replaceMonitoringPlot(
            observationId,
            monitoringPlotId,
            "justification",
            ReplacementDuration.LongTerm,
        )
      }
    }

    @Test
    fun `throws exception if monitoring plot not in observation`() {
      val otherPlotId = insertMonitoringPlot()

      assertThrows<PlotNotInObservationException> {
        service.replaceMonitoringPlot(
            observationId,
            otherPlotId,
            "justification",
            ReplacementDuration.LongTerm,
        )
      }
    }

    @Test
    fun `throws exception if plot size is different from the size of newly-created plots`() {
      val monitoringPlotId = insertMonitoringPlot(sizeMeters = MONITORING_PLOT_SIZE_INT - 1)
      insertObservationPlot()

      assertThrows<PlotSizeNotReplaceableException> {
        service.replaceMonitoringPlot(
            observationId,
            monitoringPlotId,
            "justification",
            ReplacementDuration.LongTerm,
        )
      }
    }

    @Test
    fun `throws illegal state exception if replacing ad-hoc observation plot`() {
      val observationId = insertObservation(isAdHoc = true)
      val monitoringPlotId = insertMonitoringPlot()

      assertThrows<IllegalStateException> {
        service.replaceMonitoringPlot(
            observationId,
            monitoringPlotId,
            "justification",
            ReplacementDuration.LongTerm,
        )
      }
    }

    @Test
    fun `throws access denied exception if no permission to replace plot`() {
      val monitoringPlotId = insertMonitoringPlot()
      insertObservationPlot()

      insertOrganizationUser(role = Role.Manager)

      assertThrows<AccessDeniedException> {
        service.replaceMonitoringPlot(
            observationId,
            monitoringPlotId,
            "justification",
            ReplacementDuration.LongTerm,
        )
      }
    }
  }

  @Nested
  inner class CompleteAdHocObservation {
    @BeforeEach
    fun setUp() {
      insertOrganizationUser(role = Role.Contributor)
      insertStratum()
      insertSubstratum()
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
                  statusId = RecordedPlantStatus.Live,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Live,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Dead,
              ),
              RecordedPlantsRow(
                  certaintyId = Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId1,
                  statusId = RecordedPlantStatus.Existing,
              ),
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
          "Observation table",
      )

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
          "Ad-hoc plot row",
      )
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
          "Observation plot row",
      )

      val plotSpecies1Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId,
              plotId,
              speciesId1,
              null,
              Known,
              2,
              1,
              1,
              null,
              0,
              0,
          )
      val plotSpecies2Totals =
          ObservedPlotSpeciesTotalsRow(
              observationId,
              plotId,
              speciesId2,
              null,
              Known,
              0,
              0,
              1,
              null,
              0,
              0,
          )
      val plotOther1Total =
          ObservedPlotSpeciesTotalsRow(
              observationId,
              plotId,
              null,
              "Other 1",
              Other,
              0,
              0,
              1,
              null,
              0,
              0,
          )
      val plotOther2Total =
          ObservedPlotSpeciesTotalsRow(
              observationId,
              plotId,
              null,
              "Other 2",
              Other,
              0,
              1,
              0,
              null,
              0,
              0,
          )
      val plotUnknownTotal =
          ObservedPlotSpeciesTotalsRow(
              observationId,
              plotId,
              null,
              null,
              Unknown,
              1,
              0,
              0,
              null,
              0,
              0,
          )

      helper.assertTotals(
          setOf(
              plotSpecies1Totals,
              plotSpecies2Totals,
              plotOther1Total,
              plotOther2Total,
              plotUnknownTotal,
          ),
          "Totals after observation",
      )
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
          "Observation row",
      )

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
          "Ad-hoc plot row",
      )
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
          "Observation plot record",
      )

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
          "Biomass details table",
      )
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
    fun `throws exception if planting site is not detailed`() {
      val simplePlantingSiteId = insertPlantingSite()

      assertThrows<PlantingSiteNotDetailedException> {
        service.completeAdHocObservation(
            observedTime = clock.instant.minusSeconds(1),
            observationType = ObservationType.Monitoring,
            plantingSiteId = simplePlantingSiteId,
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
    fun `abandons in-progress observations in edited strata`() {
      insertStratum(numPermanentPlots = 1, numTemporaryPlots = 1, width = 2, height = 7)
      insertSubstratum()
      val plotInEditedStratum = insertMonitoringPlot(permanentIndex = 1)
      insertStratum()
      insertSubstratum()
      val plotInNonEditedStratum = insertMonitoringPlot(permanentIndex = 1)

      val completedObservation = insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(
          monitoringPlotId = plotInEditedStratum,
          isPermanent = true,
          completedBy = user.userId,
      )
      insertObservationPlot(
          monitoringPlotId = plotInNonEditedStratum,
          isPermanent = true,
          completedBy = user.userId,
      )
      val activeObservationWithCompletedPlotInEditedStratum = insertObservation()
      insertObservationPlot(
          monitoringPlotId = plotInEditedStratum,
          isPermanent = true,
          completedBy = user.userId,
      )
      insertObservationPlot(monitoringPlotId = plotInNonEditedStratum, isPermanent = true)
      val activeObservationWithCompletedPlotInNonEditedStratum = insertObservation()
      insertObservationPlot(monitoringPlotId = plotInEditedStratum, isPermanent = true)
      insertObservationPlot(
          monitoringPlotId = plotInNonEditedStratum,
          isPermanent = true,
          completedBy = user.userId,
      )
      // Active observation with no completed plots; should be deleted
      insertObservation()
      insertObservationPlot(monitoringPlotId = plotInEditedStratum, isPermanent = true)
      insertObservationPlot(monitoringPlotId = plotInNonEditedStratum, isPermanent = true)

      val event =
          PlantingSiteMapEditedEvent(
              plantingSite,
              PlantingSiteEdit(
                  areaHaDifference = BigDecimal.ONE,
                  desiredModel = plantingSite,
                  existingModel = plantingSite,
                  stratumEdits =
                      listOf(
                          StratumEdit.Update(
                              addedRegion = rectangle(0),
                              areaHaDifference = BigDecimal.ONE,
                              desiredModel = plantingSite.strata[0],
                              existingModel = plantingSite.strata[0],
                              monitoringPlotEdits = emptyList(),
                              substratumEdits = emptyList(),
                              removedRegion = rectangle(0),
                          )
                      ),
              ),
              ReplacementResult(emptySet(), emptySet()),
          )

      service.on(event)

      assertEquals(
          mapOf(
              completedObservation to ObservationState.Completed,
              activeObservationWithCompletedPlotInEditedStratum to ObservationState.InProgress,
              activeObservationWithCompletedPlotInNonEditedStratum to ObservationState.Abandoned,
          ),
          observationsDao.findAll().associate { it.id!! to it.stateId!! },
          "Observation states",
      )
    }
  }

  @Nested
  inner class UpdateCompletedPlot {
    private lateinit var monitoringPlotId: MonitoringPlotId
    private lateinit var observationId: ObservationId

    @BeforeEach
    fun setUpPlot() {
      insertStratum()
      insertSubstratum()
      monitoringPlotId = insertMonitoringPlot()
      observationId = insertObservation()
      insertObservationPlot(completedBy = user.userId)
    }

    @Test
    fun `calls function to perform updates`() {
      val initial = dslContext.fetchSingle(OBSERVATION_PLOTS)

      service.updateCompletedPlot(observationId, monitoringPlotId) {
        dslContext.update(OBSERVATION_PLOTS).set(OBSERVATION_PLOTS.NOTES, "new notes").execute()
      }

      val expected = initial.copy().apply { notes = "new notes" }

      assertTableEquals(expected)
    }

    @Test
    fun `rolls back changes if function throws exception`() {
      val expected = dslContext.fetch(OBSERVATION_PLOTS)

      assertThrows<IllegalStateException> {
        service.updateCompletedPlot(observationId, monitoringPlotId) {
          dslContext.update(OBSERVATION_PLOTS).set(OBSERVATION_PLOTS.NOTES, "new notes").execute()
          throw IllegalStateException("oops")
        }
      }

      assertTableEquals(expected)
    }

    @Test
    fun `throws exception if plot is not in specified observation`() {
      val otherObservationId = insertObservation()

      assertThrows<ObservationPlotNotFoundException> {
        service.updateCompletedPlot(otherObservationId, monitoringPlotId) {}
      }
    }

    @Test
    fun `throws exception if plot is not completed yet`() {
      val otherPlotId = insertMonitoringPlot()
      insertObservationPlot()

      assertThrows<PlotNotCompletedException> {
        service.updateCompletedPlot(observationId, otherPlotId) {}
      }
    }

    @Test
    fun `throws exception if no permission to update observation`() {
      deleteOrganizationUser()

      assertThrows<ObservationNotFoundException> {
        service.updateCompletedPlot(observationId, monitoringPlotId) {}
      }
    }
  }

  /**
   * Inserts a permanent monitoring plot with a given index. By default, the plots are stacked
   * northward, that is, index 1 is at y=0, index 2 is at y=1, and index 3 is at y=2.
   *
   * Optionally also includes the plot in the most-recently-inserted observation.
   */
  private fun insertPermanentPlot(
      permanentIndex: Int,
      x: Int = 0,
      y: Int = (permanentIndex - 1) * 2,
      isPermanent: Boolean = false,
      insertObservationPlots: Boolean = isPermanent,
  ): MonitoringPlotId {
    val plotId = insertMonitoringPlot(permanentIndex = permanentIndex, x = x, y = y)

    if (insertObservationPlots) {
      insertObservationPlot(isPermanent = isPermanent)
    }

    return plotId
  }
}
