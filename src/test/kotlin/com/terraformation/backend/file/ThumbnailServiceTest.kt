package com.terraformation.backend.file

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.event.FileDeletionStartedEvent
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
import com.terraformation.backend.onePixelJpeg
import io.mockk.Runs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType

class ThumbnailServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val fileStore = InMemoryFileStore()
  private val converter1: JpegConverter = mockk()
  private val converter2: JpegConverter = mockk()
  private val thumbnailStore: ThumbnailStore = mockk()
  private val fileService: FileService by lazy {
    FileService(dslContext, clock, eventPublisher, filesDao, fileStore)
  }
  private val service: ThumbnailService by lazy {
    ThumbnailService(dslContext, fileService, listOf(converter1, converter2), thumbnailStore)
  }

  private val filename = "test-photo.jpg"
  private val metadata = FileMetadata.of(MediaType.IMAGE_JPEG_VALUE, filename, 1L)
  private val videoMetadata = FileMetadata.of("video/mp4", filename, 1L)

  @Nested
  inner class OnFileDeletionStartedEvent {
    @Test
    fun `deletes thumbnails`() {
      val fileId = FileId(1)

      every { thumbnailStore.deleteThumbnails(fileId) } just Runs

      service.on(FileDeletionStartedEvent(fileId, MediaType.IMAGE_JPEG_VALUE))

      verify { thumbnailStore.deleteThumbnails(fileId) }
      confirmVerified(thumbnailStore)

      assertIsEventListener<FileDeletionStartedEvent>(service)
    }
  }

  @Nested
  inner class ReadFile {
    @Test
    fun `returns original file if no dimensions specified and file is of acceptable type`() {
      val photoData = Random.nextBytes(10)
      val fileId = fileService.storeFile("category", photoData.inputStream(), metadata) {}

      val stream = service.readFile(fileId)

      assertArrayEquals(photoData, stream.readAllBytes())
    }

    @Test
    fun `returns full-sized thumbnail if no dimensions specified and file is of unacceptable type`() {
      val contentType = "image/crazy"
      val originalMetadata = metadata.copy(contentType = contentType)
      val photoData = Random.nextBytes(10)
      val thumbnailData = Random.nextBytes(10)
      val fileId = fileService.storeFile("category", photoData.inputStream(), originalMetadata) {}

      every { thumbnailStore.canGenerateThumbnails(contentType) } returns true
      every { thumbnailStore.getThumbnailData(fileId, null, null) } answers
          {
            SizedInputStream(thumbnailData.inputStream(), 10, MediaType.IMAGE_JPEG)
          }

      val stream = service.readFile(fileId)

      assertArrayEquals(thumbnailData, stream.readAllBytes())
    }

    @Test
    fun `returns thumbnail if forceThumbnail is true`() {
      val photoData = Random.nextBytes(10)
      val thumbnailData = Random.nextBytes(10)
      val fileId = fileService.storeFile("category", photoData.inputStream(), metadata) {}

      every { thumbnailStore.canGenerateThumbnails(metadata.contentType) } returns true
      every { thumbnailStore.getThumbnailData(fileId, null, null) } answers
          {
            SizedInputStream(thumbnailData.inputStream(), 10, MediaType.IMAGE_JPEG)
          }

      val stream = service.readFile(fileId, forceThumbnail = true)

      assertArrayEquals(thumbnailData, stream.readAllBytes())
    }

    @Test
    fun `returns photo thumbnail if photo dimensions are specified`() {
      val photoData = Random.nextBytes(10)
      val thumbnailData = Random.nextBytes(10)
      val thumbnailStream =
          SizedInputStream(ByteArrayInputStream(thumbnailData), thumbnailData.size.toLong())
      val width = 123
      val height = 456

      val fileId = fileService.storeFile("category", photoData.inputStream(), metadata) {}

      every { thumbnailStore.canGenerateThumbnails(metadata.contentType) } returns true
      every { thumbnailStore.getThumbnailData(any(), any(), any()) } returns thumbnailStream

      val stream = service.readFile(fileId, width, height)

      verify { thumbnailStore.getThumbnailData(fileId, width, height) }

      assertArrayEquals(thumbnailData, stream.readAllBytes())
    }

    @Test
    fun `fetches and stores JPEG image from converters if video file does not have one yet`() {
      val fileId =
          fileService.storeFile("category", Random.nextBytes(10).inputStream(), videoMetadata) {}
      val thumbnailData = Random.nextBytes(10)
      var stillImageStored = false

      every { converter1.canConvertToJpeg(videoMetadata.contentType) } returns false
      every { converter2.canConvertToJpeg(videoMetadata.contentType) } returns true
      every { converter2.convertToJpeg(fileId) } returns onePixelJpeg
      every { thumbnailStore.canGenerateThumbnails(videoMetadata.contentType) } returns false
      every { thumbnailStore.getExistingThumbnailData(fileId, 20, 20) } returns null
      every { thumbnailStore.generateThumbnailFromExistingThumbnail(fileId, 20, 20) } answers
          {
            if (stillImageStored) SizedInputStream(thumbnailData.inputStream(), 10) else null
          }
      every { thumbnailStore.storeThumbnail(fileId, any(), 1, 1) } answers
          {
            stillImageStored = true
          }

      val stream = service.readFile(fileId, 20, 20)

      verify { converter1.canConvertToJpeg(videoMetadata.contentType) }
      verify { converter2.canConvertToJpeg(videoMetadata.contentType) }
      verify { converter2.convertToJpeg(fileId) }
      verify { thumbnailStore.storeThumbnail(fileId, any(), 1, 1) }

      assertArrayEquals(thumbnailData, stream.readAllBytes())
    }

    @Test
    fun `throws exception if file does not exist`() {
      assertThrows<FileNotFoundException>("without dimensions") { service.readFile(FileId(1)) }
      assertThrows<FileNotFoundException>("with dimensions") { service.readFile(FileId(1), 5, 5) }
    }
  }
}
