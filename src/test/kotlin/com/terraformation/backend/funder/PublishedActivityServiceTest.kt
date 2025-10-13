package com.terraformation.backend.funder

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.ActivityDeletionStartedEvent
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITIES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITY_MEDIA_FILES
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.funder.db.PublishedActivityStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PublishedActivityServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val publishedActivityStore: PublishedActivityStore by lazy {
    PublishedActivityStore(clock, dslContext, eventPublisher)
  }
  private val service: PublishedActivityService by lazy {
    PublishedActivityService(publishedActivityStore)
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
}
