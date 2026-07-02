package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.BiomassStore
import com.terraformation.backend.tracking.db.ObservationLocker
import com.terraformation.backend.tracking.db.ObservationMergeNotAllowedException
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.ObservationTestHelper
import com.terraformation.backend.tracking.db.PlantingSiteNotificationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.ObservationMediaFileDeletedEvent
import com.terraformation.backend.util.GeometrySimplifier
import com.terraformation.backend.util.toPlantsPerHectare
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertThrows

class ObservationServiceMergeObservationsTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val geometrySimplifier: GeometrySimplifier = mockk()
  private val observationLocker: ObservationLocker by lazy { ObservationLocker(dslContext) }
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val fileService: FileService by lazy {
    FileService(dslContext, clock, eventPublisher, filesDao, InMemoryFileStore())
  }
  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }
  private val observationStore: ObservationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        eventPublisher,
        mockk(),
        observationLocker,
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        observationRequestedSubstrataDao,
        parentStore,
        systemUser,
    )
  }
  private val plantingSiteStore: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        TestSingletons.countryDetector,
        dslContext,
        TestEventPublisher(),
        geometrySimplifier,
        IdentifierGenerator(clock, dslContext),
        monitoringPlotsDao,
        parentStore,
        plantingSitesDao,
        eventPublisher,
        strataDao,
        substrataDao,
    )
  }

  private val service: ObservationService by lazy {
    ObservationService(
        BiomassStore(dslContext, eventPublisher, observationLocker, parentStore),
        clock,
        dslContext,
        eventPublisher,
        fileService,
        monitoringPlotsDao,
        mockk(),
        observationMediaFilesDao,
        observationLocker,
        observationStore,
        PlantingSiteNotificationStore(clock, dslContext),
        plantingSiteStore,
        eventPublisher,
        systemUser,
        mockk(),
    )
  }

  private val helper: ObservationTestHelper by lazy {
    ObservationTestHelper(this, observationStore, user.userId)
  }

  @BeforeEach
  fun setUp() {
    every { geometrySimplifier.simplify(any(), any()) } answers { firstArg() }

    insertOrganization()
    insertOrganizationUser(role = Role.Admin)
    insertUserGlobalRole(role = GlobalRole.SuperAdmin)
  }

  @Test
  fun `combines disjoint plot totals into the target site totals`() {
    helper.insertPlantedSite(numPermanentPlots = 2)
    val speciesId = inserted.speciesId
    val plotA = insertMonitoringPlot()
    val plotB = insertMonitoringPlot()
    val sourceObservationId = insertObservation()
    insertObservationRequestedSubstratum()
    val targetObservationId = insertObservation()
    insertObservationRequestedSubstratum()

    completePermanentPlot(sourceObservationId, plotA, speciesId, 1)
    completePermanentPlot(targetObservationId, plotB, speciesId, 1)

    service.mergeObservations(sourceObservationId, targetObservationId)

    assertTableEmpty(OBSERVATIONS, where = OBSERVATIONS.ID.eq(sourceObservationId))
    assertEquals(
        setOf(plotA, plotB),
        dslContext
            .select(OBSERVATION_PLOTS.MONITORING_PLOT_ID)
            .from(OBSERVATION_PLOTS)
            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(targetObservationId))
            .fetchSet(OBSERVATION_PLOTS.MONITORING_PLOT_ID),
        "Target observation plots",
    )
    assertEquals(
        2,
        dslContext.fetchValue(
            OBSERVED_SITE_SPECIES_TOTALS.TOTAL_LIVE,
            OBSERVED_SITE_SPECIES_TOTALS.OBSERVATION_ID.eq(targetObservationId)
                .and(OBSERVED_SITE_SPECIES_TOTALS.SPECIES_ID.eq(speciesId)),
        ),
        "Site total live count in target observation",
    )
  }

  @Test
  fun `conflicting plot overwrites target media and deletes the old target files`() {
    val plantingSiteId = helper.insertPlantedSite(numPermanentPlots = 2)
    val speciesId = inserted.speciesId
    val plotId = insertMonitoringPlot()
    val sourceObservationId = insertObservation()
    insertObservationRequestedSubstratum()
    val targetObservationId = insertObservation()
    insertObservationRequestedSubstratum()

    completePermanentPlot(targetObservationId, plotId, speciesId, 1)
    completePermanentPlot(sourceObservationId, plotId, speciesId, 2)

    val targetFileId = insertFile()
    insertObservationMediaFile(fileId = targetFileId, observationId = targetObservationId)
    val sourceFileId = insertFile()
    insertObservationMediaFile(fileId = sourceFileId, observationId = sourceObservationId)

    service.mergeObservations(sourceObservationId, targetObservationId)

    assertEquals(
        listOf(sourceFileId),
        dslContext
            .select(OBSERVATION_MEDIA_FILES.FILE_ID)
            .from(OBSERVATION_MEDIA_FILES)
            .where(OBSERVATION_MEDIA_FILES.OBSERVATION_ID.eq(targetObservationId))
            .fetch(OBSERVATION_MEDIA_FILES.FILE_ID),
        "Target media files after merge",
    )
    eventPublisher.assertEventPublished(FileReferenceDeletedEvent(targetFileId))
    eventPublisher.assertEventPublished(
        ObservationMediaFileDeletedEvent(
            targetFileId,
            plotId,
            targetObservationId,
            inserted.organizationId,
            plantingSiteId,
        )
    )
  }

  @Test
  fun `recalculates survival rate for the target observation`() {
    helper.insertPlantedSite(numPermanentPlots = 2)
    val speciesId = inserted.speciesId
    val plotId = insertMonitoringPlot()
    val sourceObservationId = insertObservation()
    insertObservationRequestedSubstratum()
    val targetObservationId = insertObservation()
    insertObservationRequestedSubstratum()

    // The plot's t0 baseline density was 11 plants/ha; the source observation recorded one live
    // plant in the permanent plot. After the merge the plot moves to the target, so the survival
    // rate should be recalculated for the target as 100 * 1 / 11.
    completePermanentPlot(sourceObservationId, plotId, speciesId, 1)
    insertPlotT0Density(
        monitoringPlotId = plotId,
        speciesId = speciesId,
        plotDensity = BigDecimal.valueOf(11).toPlantsPerHectare(),
    )

    service.mergeObservations(sourceObservationId, targetObservationId)

    assertEquals(
        (100.0 / 11).roundToInt(),
        dslContext.fetchValue(
            OBSERVED_SITE_SPECIES_TOTALS.SURVIVAL_RATE,
            OBSERVED_SITE_SPECIES_TOTALS.OBSERVATION_ID.eq(targetObservationId)
                .and(OBSERVED_SITE_SPECIES_TOTALS.SPECIES_ID.eq(speciesId)),
        ),
        "Target site survival rate after merge",
    )
  }

  @Test
  fun `cannot merge an observation into itself`() {
    val plantingSiteId = insertPlantingSite()
    val observationId = insertObservation(plantingSiteId = plantingSiteId)
    assertThrows<ObservationMergeNotAllowedException> {
      service.mergeObservations(observationId, observationId)
    }
  }

  @Test
  fun `cannot merge observations from different planting sites`() {
    insertPlantingSite()
    val source = insertObservation()
    insertPlantingSite()
    val target = insertObservation()

    assertThrows<ObservationMergeNotAllowedException> { service.mergeObservations(source, target) }
  }

  @Test
  fun `cannot merge ad-hoc observations`() {
    insertPlantingSite()
    val source = insertObservation(isAdHoc = true)
    val target = insertObservation(isAdHoc = true)
    assertThrows<ObservationMergeNotAllowedException> { service.mergeObservations(source, target) }
  }

  @Test
  fun `cannot merge biomass observations`() {
    insertPlantingSite()
    val source = insertObservation(observationType = ObservationType.BiomassMeasurements)
    val target = insertObservation(observationType = ObservationType.BiomassMeasurements)

    assertThrows<ObservationMergeNotAllowedException> { service.mergeObservations(source, target) }
  }

  @Test
  fun `cannot merge upcoming observations`() {
    helper.insertPlantedSite(numPermanentPlots = 2)
    val speciesId = inserted.speciesId
    val plotId = insertMonitoringPlot()
    val sourceObservationId = insertObservation()
    insertObservationRequestedSubstratum()
    completePermanentPlot(sourceObservationId, plotId, speciesId, 1)

    val targetObservationId = insertObservation(state = ObservationState.Upcoming)

    assertThrows<ObservationMergeNotAllowedException> {
      service.mergeObservations(sourceObservationId, targetObservationId)
    }

    assertNotNull(
        observationsDao.fetchOneById(sourceObservationId),
        "Source observation should not be deleted",
    )
  }

  /**
   * Adds [plotId] to [observationId] as a permanent completed plot with [liveCount] live plants.
   */
  private fun completePermanentPlot(
      observationId: ObservationId,
      plotId: MonitoringPlotId,
      speciesId: SpeciesId,
      liveCount: Int,
  ) {
    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = plotId,
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        isPermanent = true,
    )
    observationStore.completePlot(
        observationId = observationId,
        monitoringPlotId = plotId,
        conditions = emptySet(),
        notes = null,
        observedTime = Instant.EPOCH,
        plants =
            List(liveCount) {
              RecordedPlantsRow(
                  certaintyId = RecordedSpeciesCertainty.Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId,
                  statusId = RecordedPlantStatus.Live,
              )
            },
    )
  }
}
