package com.terraformation.backend.nursery.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalPhotosRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
import com.terraformation.backend.onePixelPng
import com.terraformation.backend.util.ImageUtils
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType

internal class WithdrawalPhotoServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val fileStore = InMemoryFileStore()
  private val thumbnailStore: ThumbnailStore = mockk()
  private val fileService: FileService by lazy {
    FileService(
        dslContext,
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        mockk(),
        filesDao,
        fileStore,
        thumbnailStore,
    )
  }
  private val service: WithdrawalPhotoService by lazy {
    WithdrawalPhotoService(dslContext, fileService, ImageUtils(fileStore), withdrawalPhotosDao)
  }

  private val metadata = FileMetadata.of(MediaType.IMAGE_JPEG_VALUE, "filename", 123L)
  private val withdrawalId: WithdrawalId by lazy { insertNurseryWithdrawal() }

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertFacility(type = FacilityType.Nursery)

    every { thumbnailStore.deleteThumbnails(any()) } just Runs
    every { user.canCreateWithdrawalPhoto(any()) } returns true
    every { user.canReadWithdrawal(any()) } returns true
  }

  @Test
  fun `storePhoto associates photo with withdrawal`() {
    val fileId = storePhoto()

    assertEquals(listOf(WithdrawalPhotosRow(fileId, withdrawalId)), withdrawalPhotosDao.findAll())
  }

  @Test
  fun `readPhoto returns photo data`() {
    val fileId = storePhoto(content = onePixelPng)

    val inputStream = service.readPhoto(withdrawalId, fileId)
    assertArrayEquals(onePixelPng, inputStream.readAllBytes(), "File content")
  }

  @Test
  fun `readPhoto returns thumbnail data`() {
    val content = Random.nextBytes(10)
    val fileId = storePhoto()
    val maxWidth = 10
    val maxHeight = 20

    every { thumbnailStore.getThumbnailData(fileId, maxWidth, maxHeight) } returns
        SizedInputStream(content.inputStream(), 10L)

    val inputStream = service.readPhoto(withdrawalId, fileId, maxWidth, maxHeight)
    assertArrayEquals(content, inputStream.readAllBytes(), "Thumbnail content")
  }

  @Test
  fun `readPhoto throws exception if photo is on a different withdrawal`() {
    val otherWithdrawalId = insertNurseryWithdrawal()
    val fileId = storePhoto()

    assertThrows<FileNotFoundException> { service.readPhoto(otherWithdrawalId, fileId) }
  }

  @Test
  fun `readPhoto throws exception if no permission to read withdrawal`() {
    val fileId = storePhoto()

    every { user.canReadWithdrawal(any()) } returns false

    assertThrows<WithdrawalNotFoundException> { service.readPhoto(withdrawalId, fileId) }
  }

  @Test
  fun `listPhotos returns list of withdrawal photo IDs for correct withdrawal`() {
    val fileIds = setOf(storePhoto(), storePhoto())
    val otherWithdrawalId = insertNurseryWithdrawal()
    storePhoto(otherWithdrawalId)

    assertEquals(fileIds, service.listPhotos(withdrawalId).toSet())
  }

  @Test
  fun `listPhotos throws exception if no permission to read withdrawal`() {
    every { user.canReadWithdrawal(any()) } returns false

    assertThrows<WithdrawalNotFoundException> { service.listPhotos(withdrawalId) }
  }

  @Test
  fun `handler for OrganizationDeletionStartedEvent deletes photos for all withdrawals in organization`() {
    storePhoto()
    storePhoto()

    val facilityId2 = insertFacility(type = FacilityType.Nursery)
    val facility2WithdrawalId = insertNurseryWithdrawal(facilityId = facilityId2)
    insertOrganization()
    insertFacility(type = FacilityType.Nursery)
    val otherOrgWithdrawalId = insertNurseryWithdrawal()

    storePhoto(facility2WithdrawalId)
    val otherOrgfileId = storePhoto(otherOrgWithdrawalId)

    service.on(OrganizationDeletionStartedEvent(organizationId))

    assertEquals(listOf(otherOrgfileId), filesDao.findAll().map { it.id }, "Remaining photo IDs")
    assertEquals(
        listOf(WithdrawalPhotosRow(fileId = otherOrgfileId, withdrawalId = otherOrgWithdrawalId)),
        withdrawalPhotosDao.findAll(),
        "Remaining withdrawal photos",
    )

    assertIsEventListener<OrganizationDeletionStartedEvent>(service)
  }

  private fun storePhoto(
      withdrawalId: WithdrawalId = this.withdrawalId,
      content: ByteArray = onePixelPng,
  ): FileId {
    return service.storePhoto(withdrawalId, content.inputStream(), metadata)
  }
}
