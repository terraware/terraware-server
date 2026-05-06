package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ActivityStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.ActivityStatus
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.accelerator.tables.records.ActivitiesRecord
import com.terraformation.backend.db.accelerator.tables.records.ActivityMediaFilesRecord
import com.terraformation.backend.db.accelerator.tables.records.ActivityObservationsRecord
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.tracking.db.ObservationLocker
import com.terraformation.backend.tracking.db.ObservationResultsStoreV2
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.ObservationCompletedEvent
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ObservationActivityServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }
  private val service by lazy {
    ObservationActivityService(
        ActivityStore(clock, dslContext, eventPublisher, parentStore),
        dslContext,
        ObservationResultsStoreV2(dslContext),
        ObservationStore(
            clock,
            dslContext,
            eventPublisher,
            ObservationLocker(dslContext),
            observationsDao,
            observationPlotConditionsDao,
            observationPlotsDao,
            observationRequestedSubstrataDao,
            parentStore,
        ),
        parentStore,
        PlantingSiteStore(
            clock,
            CountryDetector(),
            dslContext,
            eventPublisher,
            IdentifierGenerator(clock, dslContext),
            monitoringPlotsDao,
            parentStore,
            plantingSeasonsDao,
            plantingSitesDao,
            eventPublisher,
            strataDao,
            substrataDao,
        ),
        ProjectStore(clock, dslContext, eventPublisher, parentStore, projectsDao),
        systemUser,
    )
  }

  private lateinit var observationId: ObservationId
  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(role = Role.Manager)
    projectId = insertProject(phase = AcceleratorPhase.Phase2PlanAndScale)
    plantingSiteId = insertPlantingSite(x = 0, width = 15, projectId = projectId)

    observationId = insertObservation(completedTime = Instant.EPOCH)
  }

  @Nested
  inner class OnObservationCompletedEvent {
    @Test
    fun `creates new activity on observation completion`() {
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot()
      insertObservationPlot(completedBy = user.userId)
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
}
