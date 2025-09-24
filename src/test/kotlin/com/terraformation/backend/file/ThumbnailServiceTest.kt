package com.terraformation.backend.file

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.event.FileDeletionStartedEvent
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
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
import org.springframework.http.MediaType

class ThumbnailServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private val eventPublisher = TestEventPublisher()
  private val fileStore = InMemoryFileStore()
  private val thumbnailStore: ThumbnailStore = mockk()
  private val fileService: FileService by lazy {
    FileService(dslContext, clock, config, eventPublisher, filesDao, fileStore)
  }
  private val service: ThumbnailService by lazy {
    ThumbnailService(dslContext, fileService, thumbnailStore)
  }

  private val contentType = MediaType.IMAGE_JPEG_VALUE
  private val filename = "test-photo.jpg"
  private val metadata = FileMetadata.of(contentType, filename, 1L)

  @Nested
  inner class OnFileDeletionStartedEvent {
    @Test
    fun `deletes thumbnails`() {
      val fileId = FileId(1)

      every { thumbnailStore.deleteThumbnails(fileId) } just Runs

      service.on(FileDeletionStartedEvent(fileId))

      verify { thumbnailStore.deleteThumbnails(fileId) }
      confirmVerified(thumbnailStore)

      assertIsEventListener<FileDeletionStartedEvent>(service)
    }
  }

  @Nested
  inner class ReadFile {
    @Test
    fun `returns original file if no dimensions are specified`() {
      val photoData = Random.nextBytes(10)
      val fileId = fileService.storeFile("category", photoData.inputStream(), metadata) {}

      val stream = service.readFile(fileId)

      assertArrayEquals(photoData, stream.readAllBytes())
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

      every { thumbnailStore.getThumbnailData(any(), any(), any()) } returns thumbnailStream

      val stream = service.readFile(fileId, width, height)

      verify { thumbnailStore.getThumbnailData(fileId, width, height) }

      assertArrayEquals(thumbnailData, stream.readAllBytes())
    }
  }
}
