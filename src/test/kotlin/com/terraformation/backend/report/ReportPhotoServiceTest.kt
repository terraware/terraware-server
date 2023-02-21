package com.terraformation.backend.report

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.tables.pojos.ReportPhotosRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
import com.terraformation.backend.report.model.ReportPhotoModel
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.net.URI
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

class ReportPhotoServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val fileStore: FileStore = mockk()
  private val thumbnailStore: ThumbnailStore = mockk()
  private val fileService: FileService by lazy {
    FileService(dslContext, clock, filesDao, fileStore, thumbnailStore)
  }
  private val service: ReportPhotoService by lazy {
    ReportPhotoService(fileService, reportPhotosDao)
  }

  private lateinit var reportId: ReportId
  private var storageUrlCount = 0

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    reportId = insertReport()

    every { fileStore.delete(any()) } just Runs
    every { fileStore.newUrl(any(), any(), any()) } answers { URI("${++storageUrlCount}") }
    every { fileStore.write(any(), any()) } just Runs
    every { thumbnailStore.deleteThumbnails(any()) } just Runs
    every { user.canReadReport(any()) } returns true
    every { user.canUpdateReport(any()) } returns true
  }

  @Nested
  inner class ListPhotos {
    @Test
    fun `returns photos with captions`() {
      val fileId1 = storePhoto()
      val fileId2 = storePhoto()
      // Shouldn't include photos from other reports
      storePhoto(insertReport(year = 1990))

      reportPhotosDao.update(reportPhotosDao.fetchOneByFileId(fileId2)!!.copy(caption = "caption"))

      val expected =
          listOf(
              ReportPhotoModel(null, fileId1, reportId),
              ReportPhotoModel("caption", fileId2, reportId),
          )

      val actual = service.listPhotos(reportId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to read report`() {
      every { user.canReadReport(any()) } returns false

      assertThrows<ReportNotFoundException> { service.listPhotos(reportId) }
    }
  }

  @Nested
  inner class ReadPhoto {
    @Test
    fun `returns photo data`() {
      val content = Random.Default.nextBytes(10)
      val fileId = storePhoto(content = content)

      every { fileStore.read(URI("1")) } returns SizedInputStream(content.inputStream(), 10L)

      val inputStream = service.readPhoto(reportId, fileId)
      assertArrayEquals(content, inputStream.readAllBytes(), "File content")
    }

    @Test
    fun `returns thumbnail data`() {
      val content = Random.nextBytes(10)
      val fileId = storePhoto()
      val maxWidth = 10
      val maxHeight = 20

      every { thumbnailStore.getThumbnailData(fileId, maxWidth, maxHeight) } returns
          SizedInputStream(content.inputStream(), 10L)

      val inputStream = service.readPhoto(reportId, fileId, maxWidth, maxHeight)
      assertArrayEquals(content, inputStream.readAllBytes(), "Thumbnail content")
    }

    @Test
    fun `throws exception if photo is on a different report`() {
      val otherReportId = insertReport(year = 1990)
      val fileId = storePhoto()

      assertThrows<FileNotFoundException> { service.readPhoto(otherReportId, fileId) }
    }

    @Test
    fun `throws exception if no permission to read report`() {
      val fileId = storePhoto()

      every { user.canReadReport(any()) } returns false

      assertThrows<ReportNotFoundException> { service.readPhoto(reportId, fileId) }
    }
  }

  @Nested
  inner class StorePhoto {
    @Test
    fun `associates photo with report`() {
      val fileId = storePhoto()

      assertEquals(listOf(ReportPhotosRow(reportId, fileId)), reportPhotosDao.findAll())
    }

    @Test
    fun `throws exception if no permission to update report`() {
      every { user.canUpdateReport(any()) } returns false

      assertThrows<AccessDeniedException> { storePhoto() }
    }
  }

  @Nested
  inner class UpdatePhoto {
    @Test
    fun `updates caption`() {
      val fileId = storePhoto()
      val newCaption = "new caption"

      service.updatePhoto(ReportPhotoModel(newCaption, fileId, reportId))

      val row = reportPhotosDao.fetchOneByFileId(fileId)

      assertEquals(newCaption, row?.caption)
    }

    @Test
    fun `throws exception if no permission to update report`() {
      val fileId = storePhoto()

      every { user.canUpdateReport(any()) } returns false

      assertThrows<AccessDeniedException> {
        service.updatePhoto(ReportPhotoModel("caption", fileId, reportId))
      }
    }
  }

  private fun storePhoto(
      reportId: ReportId = this.reportId,
      content: ByteArray = ByteArray(0),
      contentType: String = MediaType.IMAGE_JPEG_VALUE
  ): FileId {
    return service.storePhoto(
        reportId, content.inputStream(), FileMetadata("upload", contentType, content.size.toLong()))
  }
}
