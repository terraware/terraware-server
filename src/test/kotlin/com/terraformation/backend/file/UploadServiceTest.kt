package com.terraformation.backend.file

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.DatabaseTest
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

  private val clock = TestClock()
  private val fileStore: FileStore = mockk()
  private val uploadStore: UploadStore by lazy {
    UploadStore(dslContext, uploadProblemsDao, uploadsDao)
  }
  private val service: UploadService by lazy {
    UploadService(clock, dslContext, fileStore, uploadsDao, uploadStore)
  }

  private val storageUrl = URI.create("file:///test")

  @BeforeEach
  fun setUp() {
    every { user.canReadUpload(any()) } returns true
    every { user.canUpdateUpload(any()) } returns true
    every { user.canDeleteUpload(any()) } returns true
  }

  @Test
  fun `receive populates uploads table`() {
    val stream = ByteArrayInputStream(ByteArray(1))
    val fileName = "test"
    val contentType = "text/csv"
    val type = UploadType.SpeciesCSV

    every { fileStore.newUrl(any(), any(), any()) } returns storageUrl
    every { fileStore.write(storageUrl, any()) } just Runs
    val organizationId = insertOrganization()

    val expected =
        listOf(
            UploadsRow(
                contentType = contentType,
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                filename = fileName,
                locale = Locales.GIBBERISH,
                organizationId = organizationId,
                statusId = UploadStatus.AwaitingValidation,
                storageUrl = storageUrl,
                typeId = type,
            )
        )

    Locales.GIBBERISH.use { service.receive(stream, fileName, contentType, type, organizationId) }

    val actual = uploadsDao.findAll()
    assertEquals(expected, actual.map { it.copy(id = null) }, "Uploads row")
  }

  @Test
  fun `receive does not leave behind uploads row if file fails to transfer to store`() {
    every { fileStore.newUrl(any(), any(), any()) } returns storageUrl
    every { fileStore.delete(storageUrl) } just Runs
    every { fileStore.write(storageUrl, any()) } throws IOException("uh oh")

    assertThrows<UploadFailedException> {
      service.receive(ByteArrayInputStream(ByteArray(1)), "test", "text/csv", UploadType.SpeciesCSV)
    }

    assertTableEmpty(UPLOADS)
    verify { fileStore.delete(storageUrl) }
  }

  @Test
  fun `delete deletes file from file store`() {
    every { fileStore.delete(storageUrl) } just Runs

    val uploadId = insertUpload(storageUrl = storageUrl)

    service.delete(uploadId)

    assertTableEmpty(UPLOADS)
    verifySequence { fileStore.delete(storageUrl) }
  }

  @Test
  fun `delete deletes file from database even if file store deletion fails`() {
    every { fileStore.delete(storageUrl) } throws NoSuchFileException("x")

    val uploadId = insertUpload(storageUrl = storageUrl)

    service.delete(uploadId)

    assertTableEmpty(UPLOADS)
  }

  @Test
  fun `delete throws exception if no permission to delete upload`() {
    every { user.canDeleteUpload(any()) } returns false

    assertThrows<AccessDeniedException> { service.delete(UploadId(1)) }
  }

  @Test
  fun `expireOldUploads only removes old uploads`() {
    val twoWeekOldStorageUrl = URI.create("file:///twoweek")
    val weekOldStorageUrl = URI.create("file:///week")
    val recentStorageUrl = URI.create("file:///recent")
    val now = Instant.EPOCH + Duration.ofDays(30)

    clock.instant = now
    every { fileStore.delete(twoWeekOldStorageUrl) } just Runs
    every { fileStore.delete(weekOldStorageUrl) } just Runs

    insertUpload(createdTime = now - Duration.ofDays(14), storageUrl = twoWeekOldStorageUrl)
    insertUpload(createdTime = now - Duration.ofDays(7), storageUrl = weekOldStorageUrl)
    val recentId =
        insertUpload(createdTime = now - Duration.ofDays(6), storageUrl = recentStorageUrl)

    service.expireOldUploads(DailyTaskTimeArrivedEvent())

    assertEquals(listOf(recentId), uploadsDao.findAll().map { it.id!! })

    verifySequence {
      fileStore.delete(twoWeekOldStorageUrl)
      fileStore.delete(weekOldStorageUrl)
    }
  }

  @Test
  fun `expireOldUploads deletes database row if file does not exist`() {
    every { fileStore.delete(storageUrl) } throws NoSuchFileException("")

    insertUpload(createdTime = clock.instant() - Duration.ofDays(7), storageUrl = storageUrl)

    service.expireOldUploads(DailyTaskTimeArrivedEvent())

    assertTableEmpty(UPLOADS)
  }

  @Test
  fun `expireOldUploads does not delete database row if file deletion fails`() {
    every { fileStore.delete(storageUrl) } throws IOException("oops")

    val uploadId =
        insertUpload(createdTime = clock.instant() - Duration.ofDays(7), storageUrl = storageUrl)

    service.expireOldUploads(DailyTaskTimeArrivedEvent())

    assertEquals(listOf(uploadId), uploadsDao.findAll().map { it.id!! })
  }

  @Test
  fun `OrganizationDeletionStartedEvent listener deletes all uploads in organization`() {
    val storageUrl1 = URI("file:///1")
    val storageUrl2 = URI("file:///1")
    val otherOrgStorageUrl = URI("file:///3")

    every { fileStore.delete(any()) } just Runs
    every { user.canReadOrganization(any()) } returns true

    val organizationId = insertOrganization()
    val otherOrganizationId = insertOrganization()
    insertUpload(organizationId = organizationId, storageUrl = storageUrl1)
    insertUpload(organizationId = organizationId, storageUrl = storageUrl2)
    val otherOrgUploadId =
        insertUpload(organizationId = otherOrganizationId, storageUrl = otherOrgStorageUrl)

    service.on(OrganizationDeletionStartedEvent(organizationId))

    assertIsEventListener<OrganizationDeletionStartedEvent>(service)

    assertEquals(
        listOf(otherOrgUploadId),
        uploadsDao.findAll().map { it.id },
        "Should still have upload for other organization",
    )

    verify { fileStore.delete(storageUrl1) }
    verify { fileStore.delete(storageUrl2) }
    confirmVerified(fileStore)
  }
}
