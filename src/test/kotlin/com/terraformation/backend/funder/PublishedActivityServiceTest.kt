package com.terraformation.backend.funder

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ActivityNotFoundException
import com.terraformation.backend.accelerator.event.ActivityDeletionStartedEvent
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITIES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITY_MEDIA_FILES
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.VideoStreamNotFoundException
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.file.mux.MuxStreamModel
import com.terraformation.backend.funder.db.PublishedActivityStore
import io.mockk.every
import io.mockk.mockk
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType

class PublishedActivityServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val muxService: MuxService = mockk()
  private val publishedActivityStore: PublishedActivityStore by lazy {
    PublishedActivityStore(clock, dslContext, eventPublisher)
  }
  private val thumbnailService: ThumbnailService = mockk()
  private val service: PublishedActivityService by lazy {
    PublishedActivityService(muxService, publishedActivityStore, thumbnailService)
  }

  private lateinit var activityId: ActivityId
  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    val cohortId = insertCohort()
    val participantId = insertParticipant(cohortId = cohortId)
    projectId = insertProject(participantId = participantId)

    activityId =
        insertActivity(
            verifiedBy = user.userId,
            verifiedTime = clock.instant(),
        )
  }

  @Nested
  inner class DeletionEventHandlers {
    private lateinit var activityFileId: FileId
    private lateinit var otherActivityId: ActivityId
    private lateinit var otherActivityFileId: FileId
    private lateinit var otherProjectActivityId: ActivityId
    private lateinit var otherProjectActivityFileId: FileId
    private lateinit var otherOrganizationActivityId: ActivityId
    private lateinit var otherOrganizationActivityFileId: FileId

    @BeforeEach
    fun setUp() {
      activityFileId = insertFile()
      insertActivityMediaFile()
      insertPublishedActivity()
      insertPublishedActivityMediaFile()

      otherActivityId = insertActivity()
      otherActivityFileId = insertFile()
      insertActivityMediaFile()
      insertPublishedActivity()
      insertPublishedActivityMediaFile()

      insertProject()
      otherProjectActivityId = insertActivity()
      otherProjectActivityFileId = insertFile()
      insertActivityMediaFile()
      insertPublishedActivity()
      insertPublishedActivityMediaFile()

      insertOrganization()
      insertOrganizationUser(role = Role.Admin)
      insertProject()
      otherOrganizationActivityId = insertActivity()
      otherOrganizationActivityFileId = insertFile()
      insertActivityMediaFile()
      insertPublishedActivity()
      insertPublishedActivityMediaFile()
    }

    @Test
    fun activityDeletionStartedEvent() {
      service.on(ActivityDeletionStartedEvent(activityId))

      eventPublisher.assertEventPublished(FileReferenceDeletedEvent(activityFileId))

      assertRemainingPublishedActivityIds(
          setOf(otherActivityId, otherProjectActivityId, otherOrganizationActivityId)
      )
      assertRemainingFileIds(
          setOf(otherActivityFileId, otherProjectActivityFileId, otherOrganizationActivityFileId)
      )

      assertIsEventListener<ActivityDeletionStartedEvent>(service)
    }

    @Test
    fun `activityDeletionStartedEvent for unpublished activity`() {
      val unpublishedActivityId = insertActivity()

      val publishedActivitiesBefore = dslContext.fetch(PUBLISHED_ACTIVITIES)
      val publishedActivityMediaBefore = dslContext.fetch(PUBLISHED_ACTIVITY_MEDIA_FILES)

      service.on(ActivityDeletionStartedEvent(unpublishedActivityId))

      eventPublisher.assertNoEventsPublished()

      assertTableEquals(publishedActivitiesBefore)
      assertTableEquals(publishedActivityMediaBefore)
    }

    @Test
    fun projectDeletionStartedEvent() {
      service.on(ProjectDeletionStartedEvent(projectId))

      eventPublisher.assertEventsPublished(
          setOf(
              FileReferenceDeletedEvent(activityFileId),
              FileReferenceDeletedEvent(otherActivityFileId),
          )
      )

      assertRemainingPublishedActivityIds(
          setOf(otherProjectActivityId, otherOrganizationActivityId)
      )
      assertRemainingFileIds(setOf(otherProjectActivityFileId, otherOrganizationActivityFileId))

      assertIsEventListener<ProjectDeletionStartedEvent>(service)
    }

    @Test
    fun organizationDeletionStartedEvent() {
      service.on(OrganizationDeletionStartedEvent(organizationId))

      eventPublisher.assertEventsPublished(
          setOf(
              FileReferenceDeletedEvent(activityFileId),
              FileReferenceDeletedEvent(otherActivityFileId),
              FileReferenceDeletedEvent(otherProjectActivityFileId),
          )
      )

      assertRemainingPublishedActivityIds(setOf(otherOrganizationActivityId))
      assertRemainingFileIds(setOf(otherOrganizationActivityFileId))

      assertIsEventListener<OrganizationDeletionStartedEvent>(service)
    }

    private fun assertRemainingPublishedActivityIds(expected: Set<ActivityId>) {
      val actual = publishedActivitiesDao.findAll().map { it.activityId!! }.toSet()

      assertEquals(expected, actual, "Remaining activity IDs")
    }

    private fun assertRemainingFileIds(expected: Set<FileId>) {
      val actual = publishedActivityMediaFilesDao.findAll().map { it.fileId!! }.toSet()

      assertSetEquals(expected, actual, "Remaining file IDs")
    }
  }

  @Nested
  inner class GetMuxStreamInfo {
    @Test
    fun `returns stream info`() {
      switchToFunderUser()
      insertFundingEntityProject()

      val fileId = insertFile()
      insertPublishedActivity()
      insertPublishedActivityMediaFile()

      val muxStream = MuxStreamModel(fileId, "playback", "token")
      every { muxService.getMuxStream(fileId, any()) } returns muxStream

      assertEquals(muxStream, service.getMuxStreamInfo(activityId, fileId))
    }

    @Test
    fun `throws exception if file has no Mux stream`() {
      switchToFunderUser()
      insertFundingEntityProject()

      val fileId = insertFile()
      insertPublishedActivity()
      insertPublishedActivityMediaFile()

      every { muxService.getMuxStream(fileId, any()) } throws VideoStreamNotFoundException(fileId)

      assertThrows<VideoStreamNotFoundException> { service.getMuxStreamInfo(activityId, fileId) }
    }

    @Test
    fun `throws exception if no permission to read activity`() {
      switchToFunderUser()

      val fileId = insertFile()
      insertPublishedActivity()
      insertPublishedActivityMediaFile()

      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<ActivityNotFoundException> { service.getMuxStreamInfo(activityId, fileId) }
    }
  }

  @Nested
  inner class ReadMedia {
    @Test
    fun `returns media data for correct activity`() {
      switchToFunderUser()
      insertFundingEntityProject()

      val fileId = insertFile()
      insertPublishedActivity()
      insertPublishedActivityMediaFile()

      val testContent = Random.nextBytes(10)
      every { thumbnailService.readFile(fileId) } answers
          {
            SizedInputStream(testContent.inputStream(), 10, MediaType.IMAGE_JPEG)
          }

      val inputStream = service.readMedia(activityId, fileId)
      assertArrayEquals(testContent, inputStream.readAllBytes(), "File content")
    }

    @Test
    fun `returns thumbnail data when dimensions specified`() {
      switchToFunderUser()
      insertFundingEntityProject()

      val fileId = insertFile()
      insertPublishedActivity()
      insertPublishedActivityMediaFile()

      val thumbnailContent = Random.nextBytes(25)
      val maxWidth = 100
      val maxHeight = 100

      every { thumbnailService.readFile(fileId, maxWidth, maxHeight) } answers
          {
            SizedInputStream(thumbnailContent.inputStream(), 25, MediaType.IMAGE_JPEG)
          }

      val inputStream = service.readMedia(activityId, fileId, maxWidth, maxHeight)
      assertArrayEquals(thumbnailContent, inputStream.readAllBytes(), "Thumbnail content")
    }

    @Test
    fun `throws exception if file not associated with activity`() {
      switchToFunderUser()
      insertFundingEntityProject()

      val fileId = insertFile()
      insertPublishedActivity()
      insertPublishedActivityMediaFile()

      val otherActivityId = insertActivity()

      assertThrows<FileNotFoundException> { service.readMedia(otherActivityId, fileId) }
    }

    @Test
    fun `throws exception if no permission to read project`() {
      switchToFunderUser()

      val fileId = insertFile()
      insertPublishedActivity()
      insertPublishedActivityMediaFile()

      assertThrows<ActivityNotFoundException> { service.readMedia(activityId, fileId) }
    }
  }

  private fun switchToFunderUser() {
    dslContext.update(USERS).set(USERS.USER_TYPE_ID, UserType.Funder).execute()
    switchToUser(user.userId)
    insertFundingEntity()
    insertFundingEntityUser()
  }
}
