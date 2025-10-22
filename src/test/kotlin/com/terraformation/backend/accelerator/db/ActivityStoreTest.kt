package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.ActivityCreatedEvent
import com.terraformation.backend.accelerator.event.ActivityDeletionStartedEvent
import com.terraformation.backend.accelerator.model.ActivityMediaModel
import com.terraformation.backend.accelerator.model.ExistingActivityModel
import com.terraformation.backend.accelerator.model.NewActivityModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.ActivityStatus
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.accelerator.tables.records.ActivitiesRecord
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.point
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ActivityStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store: ActivityStore by lazy {
    ActivityStore(clock, dslContext, eventPublisher, ParentStore(dslContext))
  }

  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    val cohortId = insertCohort()
    val participantId = insertParticipant(cohortId = cohortId)
    projectId = insertProject(participantId = participantId)
  }

  @Nested
  inner class Create {
    @Test
    fun `populates fields and publishes event`() {
      insertOrganizationUser(role = Role.Admin)

      val model =
          store.create(
              NewActivityModel(
                  activityDate = LocalDate.of(2024, 1, 15),
                  activityType = ActivityType.SeedCollection,
                  description = "Test activity description",
                  isHighlight = true,
                  projectId = projectId,
              )
          )

      assertTableEquals(
          ActivitiesRecord(
              activityDate = LocalDate.of(2024, 1, 15),
              activityStatusId = ActivityStatus.NotVerified,
              activityTypeId = ActivityType.SeedCollection,
              createdBy = currentUser().userId,
              createdTime = clock.instant(),
              description = "Test activity description",
              id = model.id,
              isHighlight = true,
              modifiedBy = currentUser().userId,
              modifiedTime = clock.instant(),
              projectId = projectId,
              verifiedBy = null,
              verifiedTime = null,
          )
      )

      eventPublisher.assertEventPublished(ActivityCreatedEvent(model.id))
    }

    @Test
    fun `populates verifiedBy and verifiedTime if creating a verified activity`() {
      insertOrganizationUser(role = Role.Admin)

      val model =
          store.create(
              NewActivityModel(
                  activityDate = LocalDate.of(2024, 1, 15),
                  activityType = ActivityType.SeedCollection,
                  description = "Test activity description",
                  isVerified = true,
                  projectId = projectId,
              )
          )

      assertTableEquals(
          ActivitiesRecord(
              activityDate = LocalDate.of(2024, 1, 15),
              activityStatusId = ActivityStatus.Verified,
              activityTypeId = ActivityType.SeedCollection,
              createdBy = currentUser().userId,
              createdTime = clock.instant(),
              description = "Test activity description",
              id = model.id,
              isHighlight = false,
              modifiedBy = currentUser().userId,
              modifiedTime = clock.instant(),
              projectId = projectId,
              verifiedBy = currentUser().userId,
              verifiedTime = clock.instant(),
          )
      )
    }

    @Test
    fun `throws exception if no permission to create activity`() {
      insertOrganizationUser(role = Role.Contributor)

      val model =
          NewActivityModel(
              activityDate = LocalDate.EPOCH,
              activityType = ActivityType.SeedCollection,
              description = "description",
              projectId = projectId,
          )

      assertThrows<AccessDeniedException> { store.create(model) }
    }

    @Test
    fun `throws exception if project is not in a cohort`() {
      insertOrganizationUser(role = Role.Admin)
      dslContext.update(PROJECTS).setNull(PROJECTS.PARTICIPANT_ID).execute()

      val model =
          NewActivityModel(
              activityDate = LocalDate.EPOCH,
              activityType = ActivityType.SeedCollection,
              description = "description",
              projectId = projectId,
          )

      assertThrows<ProjectNotInCohortException> { store.create(model) }
    }
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `fetches existing activity`() {
      insertOrganizationUser(role = Role.Admin)

      val activityId =
          insertActivity(
              activityDate = LocalDate.of(2024, 2, 20),
              activityType = ActivityType.Monitoring,
              description = "Test monitoring activity",
          )
      val publishedTime = Instant.ofEpochSecond(5858)
      insertPublishedActivity(publishedTime = publishedTime)

      val expected =
          ExistingActivityModel(
              activityDate = LocalDate.of(2024, 2, 20),
              activityStatus = ActivityStatus.NotVerified,
              activityType = ActivityType.Monitoring,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              description = "Test monitoring activity",
              id = activityId,
              isHighlight = false,
              media = emptyList(),
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              projectId = projectId,
              publishedBy = user.userId,
              publishedTime = publishedTime,
          )

      assertEquals(expected, store.fetchOneById(activityId))
    }

    @Test
    fun `includes activity media`() {
      insertOrganizationUser(role = Role.Admin)

      val activityId =
          insertActivity(
              activityDate = LocalDate.of(2024, 2, 20),
              activityType = ActivityType.Monitoring,
              description = "Test monitoring activity",
          )
      val fileId1 = insertFile(capturedLocalTime = LocalDate.of(2024, 2, 19).atStartOfDay())
      insertActivityMediaFile(
          caption = "Caption 1",
          isCoverPhoto = true,
      )
      val fileId2 =
          insertFile(
              capturedLocalTime = LocalDate.of(2024, 2, 20).atStartOfDay(),
              createdTime = Instant.ofEpochSecond(1),
              geolocation = point(1),
          )
      insertActivityMediaFile(
          caption = "Caption 2",
          isHiddenOnMap = true,
          type = ActivityMediaType.Video,
      )

      insertActivity()
      insertFile()
      insertActivityMediaFile()

      val expected =
          ExistingActivityModel(
              activityDate = LocalDate.of(2024, 2, 20),
              activityStatus = ActivityStatus.NotVerified,
              activityType = ActivityType.Monitoring,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              description = "Test monitoring activity",
              id = activityId,
              isHighlight = false,
              media =
                  listOf(
                      ActivityMediaModel(
                          activityId = activityId,
                          caption = "Caption 1",
                          capturedLocalTime = LocalDate.of(2024, 2, 19).atStartOfDay(),
                          createdBy = user.userId,
                          createdTime = Instant.EPOCH,
                          fileId = fileId1,
                          geolocation = null,
                          isCoverPhoto = true,
                          isHiddenOnMap = false,
                          listPosition = 1,
                          type = ActivityMediaType.Photo,
                      ),
                      ActivityMediaModel(
                          activityId = activityId,
                          caption = "Caption 2",
                          capturedLocalTime = LocalDate.of(2024, 2, 20).atStartOfDay(),
                          createdBy = user.userId,
                          createdTime = Instant.ofEpochSecond(1),
                          fileId = fileId2,
                          geolocation = point(1),
                          isCoverPhoto = false,
                          isHiddenOnMap = true,
                          listPosition = 2,
                          type = ActivityMediaType.Video,
                      ),
                  ),
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              projectId = projectId,
          )

      assertEquals(expected, store.fetchOneById(activityId, true))
    }

    @Test
    fun `throws ActivityNotFoundException if activity does not exist`() {
      assertThrows<ActivityNotFoundException> { store.fetchOneById(ActivityId(0)) }
    }

    @Test
    fun `throws ActivityNotFoundException if no read permission`() {
      insertOrganizationUser(role = Role.Contributor)

      val activityId = insertActivity(projectId = projectId)

      assertThrows<ActivityNotFoundException> { store.fetchOneById(activityId) }
    }
  }

  @Nested
  inner class FetchByProjectId {
    @Test
    fun `fetches activities`() {
      insertOrganizationUser(role = Role.Admin)

      val activityId1 =
          insertActivity(
              activityDate = LocalDate.of(2024, 2, 20),
              activityType = ActivityType.Monitoring,
              description = "Test monitoring activity",
          )

      val fileId1 = insertFile(capturedLocalTime = LocalDateTime.of(2025, 5, 4, 3, 2, 1))
      insertActivityMediaFile(caption = "Caption", isHiddenOnMap = true)

      val activityId2 =
          insertActivity(
              activityDate = LocalDate.of(2024, 3, 21),
              activityType = ActivityType.Planting,
              createdTime = Instant.ofEpochSecond(1),
              description = "Did some stuff",
              isHighlight = true,
              modifiedTime = Instant.ofEpochSecond(2),
          )
      val publishedTime = Instant.ofEpochSecond(5858)
      insertPublishedActivity(publishedTime = publishedTime)

      val fileId2 = insertFile(capturedLocalTime = LocalDate.EPOCH.atStartOfDay())
      insertActivityMediaFile(isCoverPhoto = true)

      // Activities for other projects shouldn't be included
      insertProject()
      insertActivity()

      val expected =
          listOf(
              ExistingActivityModel(
                  activityDate = LocalDate.of(2024, 2, 20),
                  activityStatus = ActivityStatus.NotVerified,
                  activityType = ActivityType.Monitoring,
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  description = "Test monitoring activity",
                  id = activityId1,
                  isHighlight = false,
                  media =
                      listOf(
                          ActivityMediaModel(
                              activityId = activityId1,
                              caption = "Caption",
                              capturedLocalTime = LocalDateTime.of(2025, 5, 4, 3, 2, 1),
                              createdBy = user.userId,
                              createdTime = Instant.EPOCH,
                              fileId = fileId1,
                              geolocation = null,
                              isCoverPhoto = false,
                              isHiddenOnMap = true,
                              listPosition = 1,
                              type = ActivityMediaType.Photo,
                          ),
                      ),
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  projectId = projectId,
              ),
              ExistingActivityModel(
                  activityDate = LocalDate.of(2024, 3, 21),
                  activityStatus = ActivityStatus.NotVerified,
                  activityType = ActivityType.Planting,
                  createdBy = user.userId,
                  createdTime = Instant.ofEpochSecond(1),
                  description = "Did some stuff",
                  id = activityId2,
                  isHighlight = true,
                  media =
                      listOf(
                          ActivityMediaModel(
                              activityId = activityId2,
                              caption = null,
                              capturedLocalTime = LocalDate.EPOCH.atStartOfDay(),
                              createdBy = user.userId,
                              createdTime = Instant.EPOCH,
                              fileId = fileId2,
                              geolocation = null,
                              isCoverPhoto = true,
                              isHiddenOnMap = false,
                              listPosition = 1,
                              type = ActivityMediaType.Photo,
                          ),
                      ),
                  modifiedBy = user.userId,
                  modifiedTime = Instant.ofEpochSecond(2),
                  projectId = projectId,
                  publishedBy = user.userId,
                  publishedTime = publishedTime,
              ),
          )

      assertEquals(expected, store.fetchByProjectId(projectId, includeMedia = true))
    }

    @Test
    fun `throws ProjectNotFoundException if project does not exist`() {
      assertThrows<ProjectNotFoundException> { store.fetchByProjectId(ProjectId(0)) }
    }

    @Test
    fun `throws ProjectNotFoundException if no read permission on project`() {
      assertThrows<ProjectNotFoundException> { store.fetchByProjectId(projectId) }
    }

    @Test
    fun `throws AccessDeniedException if no read permission on activities`() {
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> { store.fetchByProjectId(projectId) }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `org admin can delete activity`() {
      insertOrganizationUser(role = Role.Admin)

      val activityId = insertActivity(projectId = projectId)

      store.delete(activityId)

      assertTableEmpty(ACTIVITIES)

      eventPublisher.assertEventPublished(ActivityDeletionStartedEvent(activityId))
    }

    @Test
    fun `accelerator admin can delete activity`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val activityId = insertActivity(projectId = projectId)

      store.delete(activityId)

      assertTableEmpty(ACTIVITIES)
    }

    @Test
    fun `accelerator admin can delete published activity`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val activityId = insertActivity(projectId = projectId)
      insertPublishedActivity()

      eventPublisher.register<ActivityDeletionStartedEvent> {
        publishedActivitiesDao.deleteById(it.activityId)
      }

      store.delete(activityId)

      assertTableEmpty(ACTIVITIES)
    }

    @Test
    fun `throws ActivityNotFoundException if activity does not exist`() {
      insertOrganizationUser(role = Role.Admin)

      assertThrows<ActivityNotFoundException> { store.delete(ActivityId(0)) }
    }

    @Test
    fun `throws exception if no permission to delete activity`() {
      insertOrganizationUser(role = Role.Manager)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      val activityId = insertActivity(projectId = projectId)

      assertThrows<AccessDeniedException> { store.delete(activityId) }
    }

    @Test
    fun `throws exception if non-admin user tries to delete published activity`() {
      insertOrganizationUser(role = Role.Admin)

      val activityId = insertActivity(projectId = projectId)
      insertPublishedActivity()

      assertThrows<CannotDeletePublishedActivityException> { store.delete(activityId) }
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates activity fields`() {
      insertOrganizationUser(role = Role.Admin)

      val activityId =
          insertActivity(
              activityDate = LocalDate.of(2024, 1, 1),
              activityType = ActivityType.SeedCollection,
              description = "Original description",
              projectId = projectId,
          )

      val updateTime = Instant.ofEpochSecond(100)
      clock.instant = updateTime

      store.update(activityId) { existing ->
        existing.copy(
            activityType = ActivityType.Planting,
            activityDate = LocalDate.of(2024, 2, 1),
            description = "Updated description",
            // Updates to admin-only fields should be ignored.
            activityStatus = ActivityStatus.Verified,
            isHighlight = true,
        )
      }

      assertTableEquals(
          ActivitiesRecord(
              activityDate = LocalDate.of(2024, 2, 1),
              activityStatusId = ActivityStatus.NotVerified,
              activityTypeId = ActivityType.Planting,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              description = "Updated description",
              id = activityId,
              isHighlight = false,
              modifiedBy = user.userId,
              modifiedTime = updateTime,
              projectId = projectId,
              verifiedBy = null,
              verifiedTime = null,
          )
      )
    }

    @Test
    fun `accelerator admin can update additional fields even if activity is published`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val activityId =
          insertActivity(
              activityDate = LocalDate.of(2024, 1, 1),
              activityType = ActivityType.SeedCollection,
              description = "Original description",
              projectId = projectId,
          )
      insertPublishedActivity()

      val updateTime = Instant.ofEpochSecond(100)
      clock.instant = updateTime

      store.update(activityId) { existing ->
        existing.copy(
            activityStatus = ActivityStatus.Verified,
            activityType = ActivityType.Planting,
            activityDate = LocalDate.of(2024, 2, 1),
            description = "Updated description",
            isHighlight = true,
        )
      }

      assertTableEquals(
          ActivitiesRecord(
              activityDate = LocalDate.of(2024, 2, 1),
              activityStatusId = ActivityStatus.Verified,
              activityTypeId = ActivityType.Planting,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              description = "Updated description",
              id = activityId,
              isHighlight = true,
              modifiedBy = user.userId,
              modifiedTime = updateTime,
              projectId = projectId,
              verifiedBy = user.userId,
              verifiedTime = updateTime,
          )
      )
    }

    @Test
    fun `sets verifiedBy and verifiedTime if status goes from NotVerified to Verified`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val activityId = insertActivity()
      val record = dslContext.fetchOne(ACTIVITIES)!!

      val updateTime = Instant.ofEpochSecond(100)
      clock.instant = updateTime

      store.update(activityId) { model -> model.copy(activityStatus = ActivityStatus.Verified) }

      record.activityStatusId = ActivityStatus.Verified
      record.modifiedTime = updateTime
      record.verifiedBy = user.userId
      record.verifiedTime = updateTime

      assertTableEquals(record)
    }

    @Test
    fun `sets verifiedBy and verifiedTime if status goes from DoNotUse to Verified`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val activityId = insertActivity(activityStatus = ActivityStatus.DoNotUse)
      val record = dslContext.fetchOne(ACTIVITIES)!!

      val updateTime = Instant.ofEpochSecond(100)
      clock.instant = updateTime

      store.update(activityId) { model -> model.copy(activityStatus = ActivityStatus.Verified) }

      record.activityStatusId = ActivityStatus.Verified
      record.modifiedTime = updateTime
      record.verifiedBy = user.userId
      record.verifiedTime = updateTime

      assertTableEquals(record)
    }

    @Test
    fun `clears verifiedBy and verifiedTime if status goes from Verified to NotVerified`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val activityId = insertActivity(verifiedBy = user.userId, verifiedTime = Instant.EPOCH)
      val record = dslContext.fetchOne(ACTIVITIES)!!

      val updateTime = Instant.ofEpochSecond(100)
      clock.instant = updateTime

      store.update(activityId) { model -> model.copy(activityStatus = ActivityStatus.NotVerified) }

      record.activityStatusId = ActivityStatus.NotVerified
      record.modifiedTime = updateTime
      record.verifiedBy = null
      record.verifiedTime = null

      assertTableEquals(record)
    }

    @Test
    fun `clears verifiedBy and verifiedTime if status goes from Verified to DoNotUse`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val activityId = insertActivity(verifiedBy = user.userId, verifiedTime = Instant.EPOCH)
      val record = dslContext.fetchOne(ACTIVITIES)!!

      val updateTime = Instant.ofEpochSecond(100)
      clock.instant = updateTime

      store.update(activityId) { model -> model.copy(activityStatus = ActivityStatus.DoNotUse) }

      record.activityStatusId = ActivityStatus.DoNotUse
      record.modifiedTime = updateTime
      record.verifiedBy = null
      record.verifiedTime = null

      assertTableEquals(record)
    }

    @Test
    fun `leaves verifiedBy and verifiedTime alone if status remains Verified`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      val otherUserId = insertUser()

      val verifiedTime = Instant.ofEpochSecond(30)
      val activityId = insertActivity(verifiedBy = otherUserId, verifiedTime = verifiedTime)
      val record = dslContext.fetchOne(ACTIVITIES)!!

      val updateTime = Instant.ofEpochSecond(100)
      clock.instant = updateTime

      store.update(activityId) { it }

      record.modifiedTime = updateTime

      assertTableEquals(record)
    }

    @Test
    fun `throws ActivityNotFoundException if activity does not exist`() {
      insertOrganizationUser(role = Role.Admin)

      assertThrows<ActivityNotFoundException> {
        store.update(ActivityId(0)) { it.copy(description = "Updated") }
      }
    }

    @Test
    fun `throws exception if no permission to update activity`() {
      insertOrganizationUser(role = Role.Manager)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      val activityId = insertActivity(projectId = projectId)

      assertThrows<AccessDeniedException> {
        store.update(activityId) { it.copy(description = "Updated") }
      }
    }

    @Test
    fun `throws exception if non-admin user tries to update published activity`() {
      insertOrganizationUser(role = Role.Admin)

      val activityId = insertActivity(projectId = projectId)
      insertPublishedActivity()

      assertThrows<CannotUpdatePublishedActivityException> {
        store.update(activityId) { it.copy(description = "Updated") }
      }
    }
  }
}
