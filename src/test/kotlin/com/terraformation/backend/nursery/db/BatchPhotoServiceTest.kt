package com.terraformation.backend.nursery.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.tables.pojos.BatchPhotosRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.event.BatchDeletionStartedEvent
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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

internal class BatchPhotoServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
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
  private val service: BatchPhotoService by lazy {
    BatchPhotoService(batchPhotosDao, clock, dslContext, fileService, ImageUtils(fileStore))
  }

  private val metadata = FileMetadata.of(MediaType.IMAGE_JPEG_VALUE, "filename", 123L)

  private lateinit var batchId: BatchId
  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertSpecies()
    insertFacility(type = FacilityType.Nursery)
    batchId = insertBatch()

    every { thumbnailStore.deleteThumbnails(any()) } just Runs
    every { user.canReadBatch(any()) } returns true
    every { user.canUpdateBatch(any()) } returns true
  }

  @Nested
  inner class StorePhoto {
    @Test
    fun `associates photo with batch`() {
      val fileId = storePhoto()

      assertEquals(
          listOf(
              BatchPhotosRow(
                  batchId = batchId,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  deletedBy = null,
                  deletedTime = null,
                  fileId = fileId,
              )
          ),
          batchPhotosDao.findAll().map { it.copy(id = null) },
      )
    }

    @Test
    fun `throws exception if no permission to update batch`() {
      every { user.canUpdateBatch(any()) } returns false

      assertThrows<AccessDeniedException> { storePhoto() }
    }
  }

  @Nested
  inner class ReadPhoto {
    @Test
    fun `returns photo data`() {
      val fileId = storePhoto(content = onePixelPng)

      val inputStream = service.readPhoto(batchId, fileId)
      assertArrayEquals(onePixelPng, inputStream.readAllBytes(), "File content")
    }

    @Test
    fun `returns thumbnail data`() {
      val content = Random.nextBytes(10)
      val fileId = storePhoto()
      val maxWidth = 10
      val maxHeight = 20

      every { thumbnailStore.getThumbnailData(fileId, maxWidth, maxHeight) } returns
          SizedInputStream(content.inputStream(), 10L)

      val inputStream = service.readPhoto(batchId, fileId, maxWidth, maxHeight)
      assertArrayEquals(content, inputStream.readAllBytes(), "Thumbnail content")
    }

    @Test
    fun `throws exception if photo is on a different batch`() {
      val otherBatchId = insertBatch()
      val fileId = storePhoto()

      assertThrows<FileNotFoundException> { service.readPhoto(otherBatchId, fileId) }
    }

    @Test
    fun `throws exception if no permission to read batch`() {
      val fileId = storePhoto()

      every { user.canReadBatch(any()) } returns false

      assertThrows<BatchNotFoundException> { service.readPhoto(batchId, fileId) }
    }
  }

  @Nested
  inner class ListPhotos {
    @Test
    fun `returns photos for correct batch`() {
      val fileIds = setOf(storePhoto(), storePhoto())
      val otherBatchId = insertBatch()
      storePhoto(otherBatchId)

      assertEquals(fileIds, service.listPhotos(batchId).map { it.fileId }.toSet())
    }

    @Test
    fun `does not include deleted photos`() {
      val existingFileId = storePhoto()
      val deletedFileId = storePhoto()

      service.deletePhoto(batchId, deletedFileId)

      assertEquals(batchPhotosDao.fetchByFileId(existingFileId), service.listPhotos(batchId))
    }

    @Test
    fun `throws exception if no permission to read batch`() {
      every { user.canReadBatch(any()) } returns false

      assertThrows<BatchNotFoundException> { service.listPhotos(batchId) }
    }
  }

  @Nested
  inner class DeletePhoto {
    @Test
    fun `marks photo as deleted and removes file`() {
      val fileId = storePhoto()
      val storageUrl = filesDao.fetchOneById(fileId)!!.storageUrl!!
      val createdTime = clock.instant
      val deletedTime = createdTime.plusSeconds(1)

      clock.instant = deletedTime
      service.deletePhoto(batchId, fileId)

      assertTableEmpty(FILES)
      assertEquals(
          listOf(
              BatchPhotosRow(
                  batchId = batchId,
                  createdBy = user.userId,
                  createdTime = createdTime,
                  deletedBy = user.userId,
                  deletedTime = deletedTime,
                  fileId = null,
              )
          ),
          batchPhotosDao.findAll().map { it.copy(id = null) },
      )

      fileStore.assertFileWasDeleted(storageUrl)
    }

    @Test
    fun `throws exception if photo is on a different batch`() {
      val otherBatchId = insertBatch()
      val fileId = storePhoto()

      assertThrows<FileNotFoundException> { service.deletePhoto(otherBatchId, fileId) }
    }

    @Test
    fun `throws exception if no permission to update batch`() {
      val fileId = storePhoto()

      every { user.canUpdateBatch(any()) } returns false

      assertThrows<AccessDeniedException> { service.deletePhoto(batchId, fileId) }
    }
  }

  @Test
  fun `handler for OrganizationDeletionStartedEvent deletes photos for all batches in organization`() {
    insertFacility(type = FacilityType.Nursery)
    val facility2BatchId = insertBatch()

    insertOrganization()
    insertFacility(type = FacilityType.Nursery)
    val otherOrgBatchId = insertBatch()

    storePhoto()
    storePhoto()
    storePhoto(facility2BatchId)
    val otherOrgFileId = storePhoto(otherOrgBatchId)
    val otherBatchPhotoId = batchPhotosDao.fetchByFileId(otherOrgFileId).first().id!!

    service.on(OrganizationDeletionStartedEvent(organizationId))

    assertEquals(listOf(otherOrgFileId), filesDao.findAll().map { it.id }, "Remaining photo IDs")
    assertEquals(
        listOf(
            BatchPhotosRow(
                batchId = otherOrgBatchId,
                createdBy = user.userId,
                createdTime = clock.instant(),
                fileId = otherOrgFileId,
                id = otherBatchPhotoId,
            )
        ),
        batchPhotosDao.findAll(),
        "Remaining photos",
    )

    assertIsEventListener<OrganizationDeletionStartedEvent>(service)
  }

  @Test
  fun `handler for BatchDeletionStartedEvent deletes batch photos`() {
    val otherBatchId = insertBatch()

    storePhoto()
    storePhoto()
    val otherBatchFileId = storePhoto(otherBatchId)
    val otherBatchPhotoId = batchPhotosDao.fetchByFileId(otherBatchFileId).first().id!!

    service.on(BatchDeletionStartedEvent(batchId))

    assertEquals(listOf(otherBatchFileId), filesDao.findAll().map { it.id }, "Remaining photo IDs")
    assertEquals(
        listOf(
            BatchPhotosRow(
                batchId = otherBatchId,
                createdBy = user.userId,
                createdTime = clock.instant(),
                fileId = otherBatchFileId,
                id = otherBatchPhotoId,
            )
        ),
        batchPhotosDao.findAll(),
        "Remaining photos",
    )

    assertIsEventListener<BatchDeletionStartedEvent>(service)
  }

  private fun storePhoto(
      batchId: BatchId = this.batchId,
      content: ByteArray = onePixelPng,
  ): FileId {
    return service.storePhoto(batchId, content.inputStream(), metadata)
  }
}
