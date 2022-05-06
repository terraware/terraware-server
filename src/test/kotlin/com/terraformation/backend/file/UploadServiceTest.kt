package com.terraformation.backend.file

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UploadId
import com.terraformation.backend.db.UploadStatus
import com.terraformation.backend.db.UploadType
import com.terraformation.backend.db.tables.pojos.UploadsRow
import com.terraformation.backend.db.tables.references.UPLOADS
import com.terraformation.backend.mockUser
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.nio.file.NoSuchFileException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class UploadServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences = listOf(UPLOADS)

  private val clock: Clock = mockk()
  private val fileStore: FileStore = mockk()
  private val uploadStore: UploadStore by lazy {
    UploadStore(dslContext, uploadProblemsDao, uploadsDao)
  }
  private val service: UploadService by lazy {
    UploadService(clock, dslContext, fileStore, uploadsDao, uploadStore)
  }

  private val storageUrl = URI.create("file:///test")
  private val uploadId = UploadId(1)

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { user.canReadUpload(any()) } returns true
    every { user.canUpdateUpload(any()) } returns true
    every { user.canDeleteUpload(any()) } returns true

    insertUser()
  }

  @Test
  fun `receive populates uploads table`() {
    val stream = ByteArrayInputStream(ByteArray(1))
    val fileName = "test"
    val contentType = "text/csv"
    val type = UploadType.SpeciesCSV
    val organizationId = OrganizationId(1)

    every { fileStore.newUrl(any(), any(), any()) } returns storageUrl
    every { fileStore.write(storageUrl, any()) } just Runs
    insertOrganization(organizationId)

    val expected =
        listOf(
            UploadsRow(
                contentType = contentType,
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                filename = fileName,
                id = UploadId(1),
                organizationId = organizationId,
                statusId = UploadStatus.AwaitingValidation,
                storageUrl = storageUrl,
                typeId = type,
            ))

    val uploadId = service.receive(stream, fileName, contentType, type, organizationId)

    val actual = uploadsDao.findAll()
    assertEquals(expected[0].id, uploadId, "Upload ID")
    assertEquals(expected, actual, "Uploads row")
  }

  @Test
  fun `receive does not leave behind uploads row if file fails to transfer to store`() {
    every { fileStore.newUrl(any(), any(), any()) } returns storageUrl
    every { fileStore.delete(storageUrl) } just Runs
    every { fileStore.write(storageUrl, any()) } throws IOException("uh oh")

    assertThrows<UploadFailedException> {
      service.receive(ByteArrayInputStream(ByteArray(1)), "test", "text/csv", UploadType.SpeciesCSV)
    }

    assertEquals(emptyList<UploadsRow>(), uploadsDao.findAll())
    verify { fileStore.delete(storageUrl) }
  }

  @Test
  fun `delete deletes file from file store`() {
    every { fileStore.delete(storageUrl) } just Runs

    insertUpload(uploadId, storageUrl = storageUrl)

    service.delete(uploadId)

    assertEquals(emptyList<UploadsRow>(), uploadsDao.findAll())
    verify { fileStore.delete(storageUrl) }
  }

  @Test
  fun `delete deletes file from database even if file store deletion fails`() {
    every { fileStore.delete(storageUrl) } throws NoSuchFileException("x")

    insertUpload(uploadId, storageUrl = storageUrl)

    service.delete(uploadId)

    assertEquals(emptyList<UploadsRow>(), uploadsDao.findAll())
  }

  @Test
  fun `delete throws exception if no permission to delete upload`() {
    every { user.canDeleteUpload(any()) } returns false

    assertThrows<AccessDeniedException> { service.delete(uploadId) }
  }

  @Test
  fun `expireOldUploads only removes old uploads`() {
    val twoWeekOldId = UploadId(1)
    val weekOldId = UploadId(2)
    val recentId = UploadId(3)
    val twoWeekOldStorageUrl = URI.create("file:///twoweek")
    val weekOldStorageUrl = URI.create("file:///week")
    val recentStorageUrl = URI.create("file:///recent")
    val now = Instant.EPOCH + Duration.ofDays(30)

    every { clock.instant() } returns now
    every { fileStore.delete(twoWeekOldStorageUrl) } just Runs
    every { fileStore.delete(weekOldStorageUrl) } just Runs

    insertUpload(
        twoWeekOldId, createdTime = now - Duration.ofDays(14), storageUrl = twoWeekOldStorageUrl)
    insertUpload(weekOldId, createdTime = now - Duration.ofDays(7), storageUrl = weekOldStorageUrl)
    insertUpload(recentId, createdTime = now - Duration.ofDays(6), storageUrl = recentStorageUrl)

    service.expireOldUploads(DailyTaskTimeArrivedEvent())

    val expectedIds = listOf(recentId)

    val actualIds = dslContext.select(UPLOADS.ID).from(UPLOADS).fetch(UPLOADS.ID)
    assertEquals(expectedIds, actualIds)
    verify { fileStore.delete(twoWeekOldStorageUrl) }
    verify { fileStore.delete(weekOldStorageUrl) }
  }

  @Test
  fun `expireOldUploads deletes database row if file does not exist`() {
    every { fileStore.delete(storageUrl) } throws NoSuchFileException("")

    insertUpload(1, createdTime = clock.instant() - Duration.ofDays(7), storageUrl = storageUrl)

    service.expireOldUploads(DailyTaskTimeArrivedEvent())

    assertEquals(emptyList<UploadsRow>(), uploadsDao.findAll())
  }

  @Test
  fun `expireOldUploads does not delete database row if file deletion fails`() {
    val uploadId = UploadId(1)

    every { fileStore.delete(storageUrl) } throws IOException("oops")

    insertUpload(
        uploadId, createdTime = clock.instant() - Duration.ofDays(7), storageUrl = storageUrl)

    service.expireOldUploads(DailyTaskTimeArrivedEvent())

    val expectedIds = listOf(uploadId)

    val actualIds = dslContext.select(UPLOADS.ID).from(UPLOADS).fetch(UPLOADS.ID)
    assertEquals(expectedIds, actualIds)
  }
}
