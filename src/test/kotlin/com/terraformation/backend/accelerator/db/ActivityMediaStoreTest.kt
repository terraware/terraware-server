package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ActivityMediaStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store: ActivityMediaStore by lazy {
    ActivityMediaStore(clock, dslContext, eventPublisher)
  }

  private lateinit var activityId: ActivityId
  private lateinit var createdBy: UserId
  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    createdBy = insertUser()
    organizationId = insertOrganization()
    val projectId = insertProject(organizationId)
    activityId = insertActivity(createdBy = createdBy, projectId = projectId)

    insertOrganizationUser(role = Role.Admin)
  }

  @Nested
  inner class UpdateMedia {
    @Test
    fun `updates writable fields`() {
      val fileId =
          insertFile(capturedLocalTime = LocalDate.EPOCH.atStartOfDay(), createdBy = createdBy)
      insertActivityMediaFile()

      val activitiesBefore = dslContext.fetchSingle(ACTIVITIES)
      val activityMediaFilesBefore = dslContext.fetchSingle(ACTIVITY_MEDIA_FILES)
      val filesBefore = dslContext.fetchSingle(FILES)

      val now = Instant.ofEpochSecond(30)
      clock.instant = now

      store.updateMedia(activityId, fileId) {
        it.copy(caption = "New caption", isCoverPhoto = true)
      }

      assertTableEquals(
          activitiesBefore.also { record ->
            record.modifiedBy = user.userId
            record.modifiedTime = now
          }
      )
      assertTableEquals(
          activityMediaFilesBefore.also { record ->
            record.caption = "New caption"
            record.isCoverPhoto = true
          }
      )
      assertTableEquals(
          filesBefore.also { record ->
            record.modifiedBy = user.userId
            record.modifiedTime = now
          }
      )
    }

    @Test
    fun `moves file earlier in list if requested`() {
      val fileId1 = insertFileForActivity()
      insertActivityMediaFile(listPosition = 1)
      val fileId2 = insertFileForActivity()
      insertActivityMediaFile(listPosition = 2)
      val fileId3 = insertFileForActivity()
      insertActivityMediaFile(listPosition = 3)
      val fileId4 = insertFileForActivity()
      insertActivityMediaFile(listPosition = 4)

      store.updateMedia(activityId, fileId3) { it.copy(listPosition = 2) }

      assertListPositions(listOf(fileId1, fileId3, fileId2, fileId4))
    }

    @Test
    fun `maintains existing list position if requested`() {
      val fileId1 = insertFileForActivity()
      insertActivityMediaFile(listPosition = 1)
      val fileId2 = insertFileForActivity()
      insertActivityMediaFile(listPosition = 2)
      val fileId3 = insertFileForActivity()
      insertActivityMediaFile(listPosition = 3)

      store.updateMedia(activityId, fileId2) { it.copy(caption = "New caption") }

      assertListPositions(listOf(fileId1, fileId2, fileId3))
    }

    @Test
    fun `moves file later in list if requested`() {
      val fileId1 = insertFileForActivity()
      insertActivityMediaFile(listPosition = 1)
      val fileId2 = insertFileForActivity()
      insertActivityMediaFile(listPosition = 2)
      val fileId3 = insertFileForActivity()
      insertActivityMediaFile(listPosition = 3)
      val fileId4 = insertFileForActivity()
      insertActivityMediaFile(listPosition = 4)

      store.updateMedia(activityId, fileId2) { it.copy(listPosition = 3) }

      assertListPositions(listOf(fileId1, fileId3, fileId2, fileId4), "Moved to middle of list")

      store.updateMedia(activityId, fileId3) { it.copy(listPosition = 4) }

      assertListPositions(listOf(fileId1, fileId2, fileId4, fileId3), "Moved to end of list")
    }

    @Test
    fun `clears isCoverPhoto on existing cover photo if it is set on updated file`() {
      val fileId1 = insertFileForActivity()
      insertActivityMediaFile(isCoverPhoto = true)
      val fileId2 = insertFileForActivity()
      insertActivityMediaFile(isCoverPhoto = false)

      store.updateMedia(activityId, fileId2) { it.copy(isCoverPhoto = true) }

      assertEquals(
          mapOf(fileId1 to false, fileId2 to true),
          activityMediaFilesDao.findAll().associate { it.fileId to it.isCoverPhoto },
          "Cover photo flags",
      )
    }

    @Test
    fun `leaves isCoverPhoto alone on existing cover photo if it is not set on updated file`() {
      val fileId1 = insertFileForActivity()
      insertActivityMediaFile(isCoverPhoto = true)
      val fileId2 = insertFileForActivity()
      insertActivityMediaFile(isCoverPhoto = false)

      store.updateMedia(activityId, fileId2) { it.copy(caption = "Edited") }

      assertEquals(
          mapOf(fileId1 to true, fileId2 to false),
          activityMediaFilesDao.findAll().associate { it.fileId to it.isCoverPhoto },
          "Cover photo flags",
      )
    }

    @Test
    fun `throws exception if video is selected as cover photo`() {
      val fileId = insertFileForActivity()
      insertActivityMediaFile(type = ActivityMediaType.Video)

      assertThrows<IllegalArgumentException> {
        store.updateMedia(activityId, fileId) { it.copy(isCoverPhoto = true) }
      }
    }

    @Test
    fun `throws exception if no permission to update activity`() {
      val fileId = insertFileForActivity()
      insertActivityMediaFile()

      deleteOrganizationUser()

      assertThrows<ActivityNotFoundException> { store.updateMedia(activityId, fileId) { it } }
    }
  }

  private fun insertFileForActivity(): FileId =
      insertFile(capturedLocalTime = LocalDate.EPOCH.atStartOfDay())

  private fun assertListPositions(expected: List<FileId>, message: String? = null) {
    assertEquals(
        expected.mapIndexed { index, fileId -> fileId to index + 1 }.toMap(),
        dslContext
            .select(ACTIVITY_MEDIA_FILES.FILE_ID, ACTIVITY_MEDIA_FILES.LIST_POSITION)
            .from(ACTIVITY_MEDIA_FILES)
            .fetchMap(ACTIVITY_MEDIA_FILES.FILE_ID, ACTIVITY_MEDIA_FILES.LIST_POSITION),
        message,
    )
  }
}
