package com.terraformation.backend.file

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.tables.pojos.UploadsRow
import com.terraformation.backend.db.default_schema.tables.references.UPLOADS
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.use
import com.terraformation.backend.mockUser
import io.mockk.Runs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.nio.file.NoSuchFileException
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

  private val clock = TestClock()
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

    every { fileStore.newUrl(any(), any(), any()) } returns storageUrl
    every { fileStore.write(storageUrl, any()) } just Runs
    insertOrganization()

    val expected =
        listOf(
            UploadsRow(
                contentType = contentType,
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                filename = fileName,
                id = UploadId(1),
                locale = Locales.GIBBERISH,
                organizationId = organizationId,
                statusId = UploadStatus.AwaitingValidation,
                storageUrl = storageUrl,
                typeId = type,
            ))

    val uploadId =
        Locales.GIBBERISH.use {
          service.receive(stream, fileName, contentType, type, organizationId)
        }

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
    verifySequence { fileStore.delete(storageUrl) }
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

    clock.instant = now
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
    verifySequence {
      fileStore.delete(twoWeekOldStorageUrl)
      fileStore.delete(weekOldStorageUrl)
    }
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

  @Test
  fun `OrganizationDeletionStartedEvent listener deletes all uploads in organization`() {
    val otherOrganizationId = OrganizationId(2)
    val uploadId1 = UploadId(1)
    val uploadId2 = UploadId(2)
    val otherOrgUploadId = UploadId(3)
    val storageUrl1 = URI("file:///1")
    val storageUrl2 = URI("file:///1")
    val otherOrgStorageUrl = URI("file:///3")

    every { fileStore.delete(any()) } just Runs
    every { user.canReadOrganization(any()) } returns true

    insertOrganization(organizationId)
    insertOrganization(otherOrganizationId)
    insertUpload(uploadId1, organizationId = organizationId, storageUrl = storageUrl1)
    insertUpload(uploadId2, organizationId = organizationId, storageUrl = storageUrl2)
    insertUpload(
        otherOrgUploadId, organizationId = otherOrganizationId, storageUrl = otherOrgStorageUrl)

    service.on(OrganizationDeletionStartedEvent(organizationId))

    assertIsEventListener<OrganizationDeletionStartedEvent>(service)

    assertEquals(
        listOf(otherOrgUploadId),
        uploadsDao.findAll().map { it.id },
        "Should still have upload for other organization")

    verify { fileStore.delete(storageUrl1) }
    verify { fileStore.delete(storageUrl2) }
    confirmVerified(fileStore)
  }
}
