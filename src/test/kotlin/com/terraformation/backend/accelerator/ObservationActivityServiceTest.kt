package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ActivityMediaStore
import com.terraformation.backend.accelerator.db.ActivityStore
import com.terraformation.backend.accelerator.event.ActivityMediaUpdatedEvent
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.ActivityStatus
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.accelerator.tables.records.ActivitiesRecord
import com.terraformation.backend.db.accelerator.tables.records.ActivityMediaFilesRecord
import com.terraformation.backend.db.accelerator.tables.records.ActivityObservationsRecord
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.records.ObservationMediaFilesRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.point
import com.terraformation.backend.tracking.ObservationService
import com.terraformation.backend.tracking.db.BiomassStore
import com.terraformation.backend.tracking.db.ObservationLocker
import com.terraformation.backend.tracking.db.ObservationResultsStoreV2
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.ObservationCompletedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileDeletedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileEditedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileEditedEventValues
import com.terraformation.backend.tracking.event.ObservationMediaFileUploadedEvent
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ObservationActivityServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }
  private val observationLocker: ObservationLocker by lazy { ObservationLocker(dslContext) }
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
        CountryDetector(),
        dslContext,
        eventPublisher,
        mockk(),
        monitoringPlotsDao,
        parentStore,
        simplePlantingSeasonsDao,
        plantingSitesDao,
        eventPublisher,
        strataDao,
        substrataDao,
    )
  }
  private val service by lazy {
    ObservationActivityService(
        ActivityMediaStore(clock, dslContext, eventPublisher),
        ActivityStore(clock, dslContext, eventPublisher, parentStore),
        dslContext,
        ObservationResultsStoreV2(dslContext),
        ObservationService(
            BiomassStore(dslContext, eventPublisher, observationLocker, parentStore),
            clock,
            dslContext,
            eventPublisher,
            mockk(),
            monitoringPlotsDao,
            mockk(),
            observationMediaFilesDao,
            observationLocker,
            observationStore,
            plantingSiteStore,
            parentStore,
            eventPublisher,
            systemUser,
            mockk(),
        ),
        observationStore,
        parentStore,
        plantingSiteStore,
        ProjectStore(clock, dslContext, eventPublisher, parentStore, projectsDao),
        systemUser,
    )
  }

  private lateinit var monitoringPlotId: MonitoringPlotId
  private lateinit var observationId: ObservationId
  private lateinit var organizationId: OrganizationId
  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(role = Role.Manager)
    projectId = insertProject(phase = AcceleratorPhase.Phase2PlanAndScale)
    plantingSiteId = insertPlantingSite(x = 0, width = 15, projectId = projectId)

    observationId = insertObservation(completedTime = Instant.EPOCH)
    insertStratum()
    insertSubstratum()
    monitoringPlotId = insertMonitoringPlot()
    insertObservationPlot(completedBy = user.userId)
  }

  @Nested
  inner class OnActivityMediaUpdatedEvent {
    @Test
    fun `updates caption of observation photo`() {
      val activityId = insertActivity(activityType = ActivityType.Monitoring)
      insertActivityObservation()
      val fileId = insertFile()
      insertActivityMediaFile(caption = "New caption")
      insertObservationMediaFile(caption = "Old caption", position = null)

      service.on(
          ActivityMediaUpdatedEvent(
              activityId = activityId,
              activityType = ActivityType.Monitoring,
              caption = "New caption",
              fileId = fileId,
              triggeredBy = null,
          )
      )

      assertTableEquals(
          ObservationMediaFilesRecord(
              caption = "New caption",
              fileId = fileId,
              isOriginal = true,
              monitoringPlotId = monitoringPlotId,
              observationId = observationId,
              positionId = null,
              typeId = ObservationMediaType.Plot,
          )
      )
    }

    @Test
    fun `ignores updates that were triggered by observation media file updates`() {
      val activityId = insertActivity(activityType = ActivityType.Monitoring)
      insertActivityObservation()
      val fileId = insertFile()
      insertActivityMediaFile(caption = "Old caption")
      insertObservationMediaFile(caption = "Old caption", position = null)

      service.on(
          ActivityMediaUpdatedEvent(
              activityId = activityId,
              activityType = ActivityType.Monitoring,
              caption = "Ignored caption",
              fileId = fileId,
              triggeredBy =
                  ObservationMediaFileEditedEvent(
                      ObservationMediaFileEditedEventValues("Old caption"),
                      ObservationMediaFileEditedEventValues("Ignored caption"),
                      fileId,
                      monitoringPlotId,
                      observationId,
                      organizationId,
                      plantingSiteId,
                  ),
          )
      )

      assertTableEquals(
          ObservationMediaFilesRecord(
              caption = "Old caption",
              fileId = fileId,
              isOriginal = true,
              monitoringPlotId = monitoringPlotId,
              observationId = observationId,
              positionId = null,
              typeId = ObservationMediaType.Plot,
          )
      )

      eventPublisher.assertEventNotPublished<ObservationMediaFileEditedEvent>()
    }

    @Test
    fun `ignores updates of non-observation activity media`() {
      val activityId = insertActivity(activityType = ActivityType.Monitoring)
      val fileId = insertFile()
      insertActivityMediaFile(caption = "New caption")

      service.on(
          ActivityMediaUpdatedEvent(
              activityId = activityId,
              activityType = ActivityType.Monitoring,
              caption = "New caption",
              fileId = fileId,
              triggeredBy = null,
          )
      )

      assertTableEmpty(OBSERVATION_MEDIA_FILES)
    }
  }

  @Nested
  inner class OnObservationCompletedEvent {
    @Test
    fun `creates new activity on observation completion`() {
      val plot1File1Id = insertFile()
      insertObservationMediaFile(caption = "Caption 1")
      val plot1File2Id = insertFile()
      insertObservationMediaFile(position = null, type = ObservationMediaType.Soil)
      insertMonitoringPlot()
      insertObservationPlot()
      // Insert the quadrat photo before the corner one so we can test that the service orders
      // the files by type (corner, then quadrat, then everything else).
      val plot2File1Id = insertFile()
      insertObservationMediaFile(
          caption = "Quadrat",
          position = null,
          type = ObservationMediaType.Quadrat,
      )
      val plot2File2Id = insertFile()
      insertObservationMediaFile(
          caption = "Caption 2",
          position = ObservationPlotPosition.NorthwestCorner,
      )
      val plot2File3Id = insertFile(contentType = "video/mp4")
      insertObservationMediaFile(position = null)

      // Second observation shouldn't be included.
      insertObservation()
      insertObservationPlot()
      insertFile()
      insertObservationMediaFile()

      clock.instant = Instant.ofEpochSecond(1234)

      service.on(ObservationCompletedEvent(observationId))

      assertTableEquals(
          ActivitiesRecord(
              projectId = projectId,
              activityTypeId = ActivityType.Monitoring,
              activityDate = LocalDate.EPOCH,
              isHighlight = false,
              createdBy = systemUser.userId,
              createdTime = clock.instant,
              modifiedBy = systemUser.userId,
              modifiedTime = clock.instant,
              activityStatusId = ActivityStatus.NotVerified,
          )
      )

      val activityId = activitiesDao.findAll().single().id!!

      assertTableEquals(ActivityObservationsRecord(activityId, observationId))

      assertTableEquals(
          setOf(
              ActivityMediaFilesRecord(
                  activityId = activityId,
                  activityMediaTypeId = ActivityMediaType.Photo,
                  caption = "Caption 1",
                  fileId = plot1File1Id,
                  isCoverPhoto = false,
                  isHiddenOnMap = false,
                  listPosition = 1,
              ),
              ActivityMediaFilesRecord(
                  activityId = activityId,
                  activityMediaTypeId = ActivityMediaType.Photo,
                  caption = null,
                  fileId = plot1File2Id,
                  isCoverPhoto = false,
                  isHiddenOnMap = false,
                  listPosition = 2,
              ),
              ActivityMediaFilesRecord(
                  activityId = activityId,
                  activityMediaTypeId = ActivityMediaType.Photo,
                  caption = "Caption 2",
                  fileId = plot2File2Id,
                  isCoverPhoto = false,
                  isHiddenOnMap = false,
                  listPosition = 3,
              ),
              ActivityMediaFilesRecord(
                  activityId = activityId,
                  activityMediaTypeId = ActivityMediaType.Photo,
                  caption = "Quadrat",
                  fileId = plot2File1Id,
                  isCoverPhoto = false,
                  isHiddenOnMap = false,
                  listPosition = 4,
              ),
              ActivityMediaFilesRecord(
                  activityId = activityId,
                  activityMediaTypeId = ActivityMediaType.Video,
                  caption = null,
                  fileId = plot2File3Id,
                  isCoverPhoto = false,
                  isHiddenOnMap = false,
                  listPosition = 5,
              ),
          )
      )
    }

    @Test
    fun `does not create activity if planting site is not associated with a project`() {
      plantingSitesDao.update(
          plantingSitesDao.fetchOneById(plantingSiteId)!!.copy(projectId = null)
      )

      service.on(ObservationCompletedEvent(observationId))

      assertTableEmpty(ACTIVITIES)
    }

    @Test
    fun `does not create activity if planting site is not associated with an accelerator project`() {
      projectsDao.update(projectsDao.fetchOneById(projectId)!!.copy(phaseId = null))

      service.on(ObservationCompletedEvent(observationId))

      assertTableEmpty(ACTIVITIES)
    }
  }

  @Nested
  inner class OnObservationMediaFileDeletedEvent {
    @Test
    fun `removes activity media file`() {
      insertActivity(activityType = ActivityType.Monitoring)
      insertActivityObservation()
      insertFile(capturedLocalTime = LocalDateTime.of(2026, 2, 3, 4, 5))
      insertActivityMediaFile(caption = "Retained file", listPosition = 2)
      val retainedFileRecord = dslContext.fetchSingle(ACTIVITY_MEDIA_FILES)
      val fileId = insertFile(capturedLocalTime = LocalDateTime.of(2026, 1, 2, 3, 4))
      insertActivityMediaFile(caption = "Old caption", listPosition = 1)

      val event =
          ObservationMediaFileDeletedEvent(
              fileId,
              monitoringPlotId,
              observationId,
              organizationId,
              plantingSiteId,
          )
      service.on(event)

      // Should update list positions of remaining files
      retainedFileRecord.listPosition = 1

      assertTableEquals(retainedFileRecord)
    }
  }

  @Nested
  inner class OnObservationMediaFileEditedEvent {
    @Test
    fun `updates caption on activity media file`() {
      val activityId = insertActivity(activityType = ActivityType.Monitoring)
      insertActivityObservation()
      val fileId = insertFile(capturedLocalTime = LocalDateTime.of(2026, 1, 2, 3, 4))
      insertActivityMediaFile(caption = "Old caption")

      val event =
          ObservationMediaFileEditedEvent(
              ObservationMediaFileEditedEventValues("Old caption"),
              ObservationMediaFileEditedEventValues("New caption"),
              fileId,
              monitoringPlotId,
              observationId,
              organizationId,
              plantingSiteId,
          )
      service.on(event)

      assertTableEquals(
          ActivityMediaFilesRecord(
              fileId = fileId,
              activityId = activityId,
              activityMediaTypeId = ActivityMediaType.Photo,
              isCoverPhoto = false,
              caption = "New caption",
              isHiddenOnMap = false,
              listPosition = 1,
          )
      )

      eventPublisher.assertEventPublished(
          ActivityMediaUpdatedEvent(
              activityId = activityId,
              activityType = ActivityType.Monitoring,
              caption = "New caption",
              fileId = fileId,
              triggeredBy = event,
          )
      )
    }

    @Test
    fun `ignores update of media file when observation has no activity`() {
      val fileId = insertFile()
      insertObservationMediaFile()

      service.on(
          ObservationMediaFileEditedEvent(
              ObservationMediaFileEditedEventValues("Old caption"),
              ObservationMediaFileEditedEventValues("New caption"),
              fileId,
              monitoringPlotId,
              observationId,
              organizationId,
              plantingSiteId,
          )
      )

      assertTableEmpty(ACTIVITY_MEDIA_FILES)

      eventPublisher.assertEventNotPublished<ActivityMediaUpdatedEvent>()
    }
  }

  @Nested
  inner class OnObservationMediaFileUploadedEvent {
    @Test
    fun `creates activity media file for new observation media file`() {
      val activityId = insertActivity(activityType = ActivityType.Monitoring)
      insertActivityObservation()
      val existingFileId = insertFile()
      insertObservationMediaFile()
      insertActivityMediaFile(caption = "Existing", type = ActivityMediaType.Video)
      val fileId = insertFile(capturedLocalTime = LocalDateTime.of(2026, 1, 2, 3, 4))
      insertObservationMediaFile(caption = "Caption", isOriginal = false, position = null)

      val event =
          ObservationMediaFileUploadedEvent(
              "Caption",
              "image/jpeg",
              fileId,
              point(1),
              false,
              monitoringPlotId,
              observationId,
              organizationId,
              plantingSiteId,
              null,
              ObservationMediaType.Plot,
          )

      service.on(event)

      assertTableEquals(
          setOf(
              ActivityMediaFilesRecord(
                  existingFileId,
                  activityId,
                  ActivityMediaType.Video,
                  false,
                  "Existing",
                  false,
                  1,
              ),
              ActivityMediaFilesRecord(
                  fileId,
                  activityId,
                  ActivityMediaType.Photo,
                  false,
                  "Caption",
                  false,
                  2,
              ),
          )
      )
    }

    @Test
    fun `ignores uploaded files for observations without activities`() {
      val fileId = insertFile(capturedLocalTime = LocalDateTime.of(2026, 1, 2, 3, 4))
      insertObservationMediaFile(caption = "Caption", isOriginal = false, position = null)

      val event =
          ObservationMediaFileUploadedEvent(
              "Caption",
              "image/jpeg",
              fileId,
              point(1),
              false,
              monitoringPlotId,
              observationId,
              organizationId,
              plantingSiteId,
              null,
              ObservationMediaType.Plot,
          )

      service.on(event)

      assertTableEmpty(ACTIVITY_MEDIA_FILES)
    }
  }
}
