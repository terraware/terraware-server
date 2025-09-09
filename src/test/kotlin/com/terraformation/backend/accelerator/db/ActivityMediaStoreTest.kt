package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.references.FILES
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ActivityMediaStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val store: ActivityMediaStore by lazy { ActivityMediaStore(clock, dslContext) }

  private lateinit var activityId: ActivityId
  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    val projectId = insertProject(organizationId)
    activityId = insertActivity(projectId = projectId)

    insertOrganizationUser(role = Role.Admin)
  }

  @Nested
  inner class UpdateMedia {
    @Test
    fun `updates writable fields`() {
      val otherUserId = insertUser()
      val fileId = insertFile(createdBy = otherUserId)
      insertActivityMediaFile()

      val filesBefore = dslContext.selectFrom(FILES).fetch()
      val activityMediaFilesBefore = dslContext.selectFrom(ACTIVITY_MEDIA_FILES).fetch()

      val now = Instant.ofEpochSecond(30)
      clock.instant = now

      store.updateMedia(activityId, fileId) {
        it.copy(caption = "New caption", isCoverPhoto = true)
      }

      assertTableEquals(
          activityMediaFilesBefore.first().also { record ->
            record.caption = "New caption"
            record.isCoverPhoto = true
          }
      )
      assertTableEquals(
          filesBefore.first().also { record ->
            record.modifiedBy = user.userId
            record.modifiedTime = now
          }
      )
    }

    @Test
    fun `clears isCoverPhoto on existing cover photo if it is set on updated file`() {
      val fileId1 = insertFile()
      insertActivityMediaFile(isCoverPhoto = true)
      val fileId2 = insertFile()
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
      val fileId1 = insertFile()
      insertActivityMediaFile(isCoverPhoto = true)
      val fileId2 = insertFile()
      insertActivityMediaFile(isCoverPhoto = false)

      store.updateMedia(activityId, fileId2) { it.copy(caption = "Edited") }

      assertEquals(
          mapOf(fileId1 to true, fileId2 to false),
          activityMediaFilesDao.findAll().associate { it.fileId to it.isCoverPhoto },
          "Cover photo flags",
      )
    }

    @Test
    fun `throws exception if no permission to update activity`() {
      val fileId = insertFile()
      insertActivityMediaFile()

      deleteOrganizationUser()

      assertThrows<ActivityNotFoundException> { store.updateMedia(activityId, fileId) { it } }
    }
  }
}
