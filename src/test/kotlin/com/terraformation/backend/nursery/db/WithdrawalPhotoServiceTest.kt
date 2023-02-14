package com.terraformation.backend.nursery.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.PhotoNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.PhotoId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalPhotosRow
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.PhotoService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.model.PhotoMetadata
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.event.WithdrawalDeletionStartedEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.net.URI
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

  private val fileStore: FileStore = mockk()
  private val thumbnailStore: ThumbnailStore = mockk()
  private val photoService: PhotoService by lazy {
    PhotoService(
        dslContext,
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        fileStore,
        photosDao,
        thumbnailStore)
  }
  private val service: WithdrawalPhotoService by lazy {
    WithdrawalPhotoService(dslContext, photoService, withdrawalPhotosDao)
  }

  private val metadata = PhotoMetadata("filename", MediaType.IMAGE_JPEG_VALUE, 123L)
  private val withdrawalId: WithdrawalId by lazy { insertWithdrawal() }
  private var storageUrlCount = 0

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertFacility(type = FacilityType.Nursery)

    every { fileStore.delete(any()) } just Runs
    every { fileStore.newUrl(any(), any(), any()) } answers { URI("${++storageUrlCount}") }
    every { fileStore.write(any(), any()) } just Runs
    every { thumbnailStore.deleteThumbnails(any()) } just Runs
    every { user.canCreateWithdrawalPhoto(any()) } returns true
    every { user.canReadWithdrawal(any()) } returns true
  }

  @Test
  fun `storePhoto associates photo with withdrawal`() {
    val photoId = storePhoto()

    assertEquals(listOf(WithdrawalPhotosRow(photoId, withdrawalId)), withdrawalPhotosDao.findAll())
  }

  @Test
  fun `readPhoto returns photo data`() {
    val content = Random.nextBytes(10)
    val photoId = storePhoto(content = content)

    every { fileStore.read(URI("1")) } returns SizedInputStream(content.inputStream(), 10L)

    val inputStream = service.readPhoto(withdrawalId, photoId)
    assertArrayEquals(content, inputStream.readAllBytes(), "File content")
  }

  @Test
  fun `readPhoto returns thumbnail data`() {
    val content = Random.nextBytes(10)
    val photoId = storePhoto()
    val maxWidth = 10
    val maxHeight = 20

    every { thumbnailStore.getThumbnailData(photoId, maxWidth, maxHeight) } returns
        SizedInputStream(content.inputStream(), 10L)

    val inputStream = service.readPhoto(withdrawalId, photoId, maxWidth, maxHeight)
    assertArrayEquals(content, inputStream.readAllBytes(), "Thumbnail content")
  }

  @Test
  fun `readPhoto throws exception if photo is on a different withdrawal`() {
    val otherWithdrawalId = insertWithdrawal()
    val photoId = storePhoto()

    assertThrows<PhotoNotFoundException> { service.readPhoto(otherWithdrawalId, photoId) }
  }

  @Test
  fun `readPhoto throws exception if no permission to read withdrawal`() {
    val photoId = storePhoto()

    every { user.canReadWithdrawal(any()) } returns false

    assertThrows<WithdrawalNotFoundException> { service.readPhoto(withdrawalId, photoId) }
  }

  @Test
  fun `listPhotos returns list of withdrawal photo IDs for correct withdrawal`() {
    val photoIds = setOf(storePhoto(), storePhoto())
    val otherWithdrawalId = insertWithdrawal()
    storePhoto(otherWithdrawalId)

    assertEquals(photoIds, service.listPhotos(withdrawalId).toSet())
  }

  @Test
  fun `listPhotos throws exception if no permission to read withdrawal`() {
    every { user.canReadWithdrawal(any()) } returns false

    assertThrows<WithdrawalNotFoundException> { service.listPhotos(withdrawalId) }
  }

  @Test
  fun `handler for OrganizationDeletionStartedEvent deletes photos for all withdrawals in organization`() {
    val facilityId2 = FacilityId(2)
    val otherOrganizationId = OrganizationId(2)
    val otherOrgFacilityId = FacilityId(3)
    insertOrganization(otherOrganizationId)
    insertFacility(facilityId2, type = FacilityType.Nursery)
    insertFacility(otherOrgFacilityId, otherOrganizationId, type = FacilityType.Nursery)
    val facility2WithdrawalId = insertWithdrawal(facilityId = facilityId2)
    val otherOrgWithdrawalId = insertWithdrawal(facilityId = otherOrgFacilityId)

    storePhoto()
    storePhoto()
    storePhoto(facility2WithdrawalId)
    val otherOrgPhotoId = storePhoto(otherOrgWithdrawalId)

    service.on(OrganizationDeletionStartedEvent(organizationId))

    assertEquals(listOf(otherOrgPhotoId), photosDao.findAll().map { it.id }, "Remaining photo IDs")
    assertEquals(
        listOf(WithdrawalPhotosRow(photoId = otherOrgPhotoId, withdrawalId = otherOrgWithdrawalId)),
        withdrawalPhotosDao.findAll(),
        "Remaining withdrawal photos")

    assertIsEventListener<OrganizationDeletionStartedEvent>(service)
  }

  @Test
  fun `handler for WithdrawalDeletionStartedEvent deletes withdrawal photos`() {
    val otherWithdrawalId = insertWithdrawal()

    storePhoto()
    storePhoto()
    val otherWithdrawalPhotoId = storePhoto(otherWithdrawalId)

    service.on(WithdrawalDeletionStartedEvent(withdrawalId))

    assertEquals(
        listOf(otherWithdrawalPhotoId), photosDao.findAll().map { it.id }, "Remaining photo IDs")
    assertEquals(
        listOf(
            WithdrawalPhotosRow(
                photoId = otherWithdrawalPhotoId, withdrawalId = otherWithdrawalId)),
        withdrawalPhotosDao.findAll(),
        "Remaining withdrawal photos")

    assertIsEventListener<WithdrawalDeletionStartedEvent>(service)
  }

  private fun storePhoto(
      withdrawalId: WithdrawalId = this.withdrawalId,
      content: ByteArray = ByteArray(0)
  ): PhotoId {
    return service.storePhoto(withdrawalId, content.inputStream(), content.size.toLong(), metadata)
  }
}
