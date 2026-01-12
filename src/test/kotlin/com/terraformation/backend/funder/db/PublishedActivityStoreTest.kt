package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ActivityNotFoundException
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.ActivityStatus
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.funder.tables.records.PublishedActivitiesRecord
import com.terraformation.backend.db.funder.tables.records.PublishedActivityMediaFilesRecord
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.funder.model.PublishedActivityMediaModel
import com.terraformation.backend.funder.model.PublishedActivityModel
import com.terraformation.backend.point
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PublishedActivityStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store: PublishedActivityStore by lazy {
    PublishedActivityStore(clock, dslContext, eventPublisher)
  }

  private lateinit var activityId: ActivityId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    projectId = insertProject()

    activityId =
        insertActivity(
            activityDate = LocalDate.of(2024, 1, 15),
            activityStatus = ActivityStatus.Verified,
            activityType = ActivityType.SeedCollection,
            description = "Test activity",
            isHighlight = true,
            verifiedBy = user.userId,
            verifiedTime = Instant.EPOCH,
        )
  }

  @Nested
  inner class FetchByProjectId {
    @Test
    fun `lists published activities for funder user who has access to project`() {
      switchToFunder()

      val activityId1 = insertActivity(isHighlight = true, verifiedBy = user.userId)
      insertPublishedActivity(
          activityDate = LocalDate.of(2025, 1, 1),
          activityType = ActivityType.SeedCollection,
          description = "Activity 1",
          isHighlight = true,
      )
      val activity1FileId1 =
          insertFile(
              capturedLocalTime = LocalDate.EPOCH.atStartOfDay(),
              geolocation = point(0),
              storageUrl = "s3://x/y/my-file.jpg",
          )
      insertActivityMediaFile()
      insertPublishedActivityMediaFile()
      val activity1FileId2 = insertFile(capturedLocalTime = LocalDate.EPOCH.atStartOfDay())
      insertActivityMediaFile()
      insertPublishedActivityMediaFile()
      val activityId2 = insertActivity(verifiedBy = user.userId)
      insertPublishedActivity(
          activityDate = LocalDate.of(2025, 1, 2),
          activityType = ActivityType.Monitoring,
          description = "Activity 2",
      )
      val activity2FileId = insertFile(capturedLocalTime = LocalDate.EPOCH.atStartOfDay())
      insertActivityMediaFile()
      insertPublishedActivityMediaFile()

      // Activities from other projects shouldn't be included.
      insertProject()
      insertFundingEntityProject()
      insertActivity(verifiedBy = user.userId)
      insertPublishedActivity()

      assertEquals(
          listOf(
              PublishedActivityModel(
                  activityDate = LocalDate.of(2025, 1, 1),
                  activityType = ActivityType.SeedCollection,
                  description = "Activity 1",
                  id = activityId1,
                  isHighlight = true,
                  media =
                      listOf(
                          PublishedActivityMediaModel(
                              activityId = activityId1,
                              caption = null,
                              capturedLocalTime = LocalDate.EPOCH.atStartOfDay(),
                              fileId = activity1FileId1,
                              fileName = "my-file.jpg",
                              geolocation = point(0),
                              isCoverPhoto = false,
                              isHiddenOnMap = false,
                              listPosition = 1,
                              type = ActivityMediaType.Photo,
                          ),
                          PublishedActivityMediaModel(
                              activityId = activityId1,
                              caption = null,
                              capturedLocalTime = LocalDate.EPOCH.atStartOfDay(),
                              fileId = activity1FileId2,
                              fileName = "1",
                              geolocation = null,
                              isCoverPhoto = false,
                              isHiddenOnMap = false,
                              listPosition = 2,
                              type = ActivityMediaType.Photo,
                          ),
                      ),
                  projectId = projectId,
                  publishedBy = user.userId,
                  publishedTime = Instant.EPOCH,
              ),
              PublishedActivityModel(
                  activityDate = LocalDate.of(2025, 1, 2),
                  activityType = ActivityType.Monitoring,
                  description = "Activity 2",
                  id = activityId2,
                  isHighlight = false,
                  media =
                      listOf(
                          PublishedActivityMediaModel(
                              activityId = activityId2,
                              caption = null,
                              capturedLocalTime = LocalDate.EPOCH.atStartOfDay(),
                              fileId = activity2FileId,
                              fileName = "2",
                              geolocation = null,
                              isCoverPhoto = false,
                              isHiddenOnMap = false,
                              listPosition = 1,
                              type = ActivityMediaType.Photo,
                          ),
                      ),
                  projectId = projectId,
                  publishedBy = user.userId,
                  publishedTime = Instant.EPOCH,
              ),
          ),
          store.fetchByProjectId(projectId, includeMedia = true),
      )
    }

    @Test
    fun `does not include media if not requested`() {
      switchToFunder()

      val activityId1 = insertActivity(isHighlight = true, verifiedBy = user.userId)
      insertPublishedActivity(
          activityDate = LocalDate.of(2025, 1, 1),
          activityType = ActivityType.SeedCollection,
          description = "Activity 1",
          isHighlight = true,
      )
      insertFile()
      insertActivityMediaFile()
      insertPublishedActivityMediaFile()

      assertEquals(
          listOf(
              PublishedActivityModel(
                  activityDate = LocalDate.of(2025, 1, 1),
                  activityType = ActivityType.SeedCollection,
                  description = "Activity 1",
                  id = activityId1,
                  isHighlight = true,
                  media = emptyList(),
                  projectId = projectId,
                  publishedBy = user.userId,
                  publishedTime = Instant.EPOCH,
              ),
          ),
          store.fetchByProjectId(projectId, includeMedia = false),
      )
    }

    @Test
    fun `throws exception for funder user who does not have access to project`() {
      switchToFunder()
      dslContext.deleteFrom(FUNDING_ENTITY_PROJECTS).execute()

      assertThrows<ProjectNotFoundException> {
        store.fetchByProjectId(projectId, includeMedia = false)
      }
    }

    private fun switchToFunder() {
      dslContext.update(USERS).set(USERS.USER_TYPE_ID, UserType.Funder).execute()
      switchToUser(user.userId)
      insertFundingEntity()
      insertFundingEntityUser()
      insertFundingEntityProject()
    }
  }

  @Nested
  inner class Publish {
    @Test
    fun `publishes activity and media files`() {
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      val fileId1 = insertFile(capturedLocalTime = LocalDate.of(2024, 1, 10).atStartOfDay())
      insertActivityMediaFile(caption = "Photo caption", isCoverPhoto = true)

      val fileId2 = insertFile(geolocation = point(1))
      insertActivityMediaFile(
          caption = "Video caption",
          isHiddenOnMap = true,
          type = ActivityMediaType.Video,
      )

      store.publish(activityId)

      assertTableEquals(
          PublishedActivitiesRecord(
              activityDate = LocalDate.of(2024, 1, 15),
              activityId = activityId,
              activityTypeId = ActivityType.SeedCollection,
              description = "Test activity",
              isHighlight = true,
              projectId = projectId,
              publishedBy = user.userId,
              publishedTime = Instant.EPOCH,
          )
      )

      assertTableEquals(
          listOf(
              PublishedActivityMediaFilesRecord(
                  activityId = activityId,
                  activityMediaTypeId = ActivityMediaType.Photo,
                  caption = "Photo caption",
                  fileId = fileId1,
                  isCoverPhoto = true,
                  isHiddenOnMap = false,
                  listPosition = 1,
              ),
              PublishedActivityMediaFilesRecord(
                  activityId = activityId,
                  activityMediaTypeId = ActivityMediaType.Video,
                  caption = "Video caption",
                  fileId = fileId2,
                  isCoverPhoto = false,
                  isHiddenOnMap = true,
                  listPosition = 2,
              ),
          )
      )
    }

    @Test
    fun `updates existing published activity on republish`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      store.publish(activityId)

      dslContext
          .update(ACTIVITIES)
          .set(ACTIVITIES.DESCRIPTION, "Updated description")
          .set(ACTIVITIES.IS_HIGHLIGHT, false)
          .where(ACTIVITIES.ID.eq(activityId))
          .execute()

      clock.instant = Instant.ofEpochSecond(100)

      store.publish(activityId)

      assertTableEquals(
          PublishedActivitiesRecord(
              activityId = activityId,
              projectId = projectId,
              activityTypeId = ActivityType.SeedCollection,
              activityDate = LocalDate.of(2024, 1, 15),
              description = "Updated description",
              isHighlight = false,
              publishedBy = user.userId,
              publishedTime = clock.instant(),
          )
      )
    }

    @Test
    fun `removes media files that are no longer present on original activity`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val fileId1 = insertFile()
      insertActivityMediaFile()
      val fileId2 = insertFile()
      insertActivityMediaFile()

      store.publish(activityId)

      activityMediaFilesDao.deleteById(fileId2)

      store.publish(activityId)

      assertEquals(
          listOf(fileId1),
          activityMediaFilesDao.findAll().map { it.fileId },
          "Remaining files",
      )

      eventPublisher.assertEventPublished(FileReferenceDeletedEvent(fileId2))
    }

    @Test
    fun `publishes newly-added and modified media files`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val fileId1 =
          insertFile(
              capturedLocalTime = LocalDate.of(2024, 1, 1).atStartOfDay(),
              geolocation = point(0),
          )
      insertActivityMediaFile(
          caption = "Original caption",
          isCoverPhoto = true,
      )

      store.publish(activityId)

      dslContext
          .update(ACTIVITY_MEDIA_FILES)
          .set(ACTIVITY_MEDIA_FILES.CAPTION, "Updated caption")
          .set(ACTIVITY_MEDIA_FILES.IS_COVER_PHOTO, false)
          .set(ACTIVITY_MEDIA_FILES.IS_HIDDEN_ON_MAP, true)
          .set(ACTIVITY_MEDIA_FILES.LIST_POSITION, 2)
          .where(ACTIVITY_MEDIA_FILES.FILE_ID.eq(fileId1))
          .execute()

      val fileId2 =
          insertFile(
              capturedLocalTime = LocalDate.of(2024, 1, 2).atStartOfDay(),
              geolocation = point(1, 2),
          )
      insertActivityMediaFile(
          caption = "New video file",
          listPosition = 1,
          type = ActivityMediaType.Video,
      )

      val filesBefore = dslContext.fetch(FILES)

      store.publish(activityId)

      assertTableEquals(
          listOf(
              PublishedActivityMediaFilesRecord(
                  activityId = activityId,
                  fileId = fileId2,
                  activityMediaTypeId = ActivityMediaType.Video,
                  caption = "New video file",
                  isCoverPhoto = false,
                  isHiddenOnMap = false,
                  listPosition = 1,
              ),
              PublishedActivityMediaFilesRecord(
                  activityId = activityId,
                  fileId = fileId1,
                  activityMediaTypeId = ActivityMediaType.Photo,
                  caption = "Updated caption",
                  isCoverPhoto = false,
                  isHiddenOnMap = true,
                  listPosition = 2,
              ),
          )
      )

      assertTableEquals(filesBefore)
    }

    @Test
    fun `throws exception for unverified activity`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val unverifiedActivityId = insertActivity()

      assertThrows<CannotPublishUnverifiedActivityException> { store.publish(unverifiedActivityId) }
    }

    @Test
    fun `throws CannotPublishUnverifiedActivityException for DoNotUse activity`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val doNotUseActivityId = insertActivity(activityStatus = ActivityStatus.DoNotUse)

      assertThrows<CannotPublishUnverifiedActivityException> { store.publish(doNotUseActivityId) }
    }

    @Test
    fun `throws ActivityNotFoundException if activity does not exist`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      assertThrows<ActivityNotFoundException> { store.publish(ActivityId(9999)) }
    }

    @Test
    fun `throws ActivityNotFoundException if no permission to manage activity`() {
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<ActivityNotFoundException> { store.publish(activityId) }
    }
  }
}
