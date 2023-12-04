package com.terraformation.backend.report

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.tables.pojos.ReportFilesRow
import com.terraformation.backend.db.default_schema.tables.pojos.ReportPhotosRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.model.ExistingFileMetadata
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
import com.terraformation.backend.report.db.ReportStore
import com.terraformation.backend.report.event.ReportDeletionStartedEvent
import com.terraformation.backend.report.model.ReportFileModel
import com.terraformation.backend.report.model.ReportPhotoModel
import io.mockk.Runs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.net.URI
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

class ReportFileServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val fileStore: FileStore = mockk()
  private val thumbnailStore: ThumbnailStore = mockk()
  private val fileService: FileService by lazy {
    FileService(dslContext, clock, mockk(), filesDao, fileStore, thumbnailStore)
  }
  private val reportStore: ReportStore by lazy {
    ReportStore(
        clock, dslContext, TestEventPublisher(), jacksonObjectMapper(), reportsDao, facilitiesDao)
  }
  private val service: ReportFileService by lazy {
    ReportFileService(filesDao, fileService, reportFilesDao, reportPhotosDao, reportStore)
  }

  private val excelContentType = "application/vnd.ms-excel"
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
  inner class DeleteReport {
    @Test
    fun `report deletion triggers file and photo deletion`() {
      excludeRecords { fileStore.newUrl(any(), any(), any()) }
      excludeRecords { fileStore.write(any(), any()) }

      storeFile(filename = "a.txt")
      storeFile(filename = "b.txt")
      storePhoto()
      storePhoto()

      val storageUrls = filesDao.findAll().map { it.storageUrl!! }

      service.on(ReportDeletionStartedEvent(reportId))

      assertEquals(emptyList<ReportPhotosRow>(), reportPhotosDao.findAll(), "Report photos")
      assertEquals(emptyList<ReportFilesRow>(), reportFilesDao.findAll(), "Report files")

      storageUrls.forEach { verify { fileStore.delete(it) } }
      confirmVerified(fileStore)

      assertIsEventListener<ReportDeletionStartedEvent>(service)
    }
  }

  @Nested
  inner class ListFiles {
    @Test
    fun `returns files with filenames`() {
      val fileId1 = storeFile(filename = "file1.xls")
      val fileId2 = storeFile(filename = "file2.xls", content = byteArrayOf(1, 2, 3))
      // Shouldn't include photos from other reports
      storeFile(insertReport(year = 1990), filename = "file3.xls")

      val expected =
          listOf(
              ReportFileModel(
                  ExistingFileMetadata(excelContentType, "file1.xls", fileId1, 0, URI("1")),
                  reportId),
              ReportFileModel(
                  ExistingFileMetadata(excelContentType, "file2.xls", fileId2, 3, URI("2")),
                  reportId),
          )

      val actual = service.listFiles(reportId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to read report`() {
      every { user.canReadReport(any()) } returns false

      assertThrows<ReportNotFoundException> { service.listFiles(reportId) }
    }
  }

  @Nested
  inner class ListPhotos {
    @Test
    fun `returns photos with captions`() {
      val fileId1 = storePhoto(filename = "photo1.jpg")
      val fileId2 = storePhoto(filename = "photo2.png", contentType = MediaType.IMAGE_PNG_VALUE)
      // Shouldn't include photos from other reports
      storePhoto(insertReport(year = 1990))

      reportPhotosDao.update(reportPhotosDao.fetchOneByFileId(fileId2)!!.copy(caption = "caption"))

      val expected =
          listOf(
              ReportPhotoModel(
                  null,
                  ExistingFileMetadata(
                      MediaType.IMAGE_JPEG_VALUE, "photo1.jpg", fileId1, 0, URI("1")),
                  reportId),
              ReportPhotoModel(
                  "caption",
                  ExistingFileMetadata(
                      MediaType.IMAGE_PNG_VALUE, "photo2.png", fileId2, 0, URI("2")),
                  reportId),
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
  inner class ReadFile {
    @Test
    fun `returns file data`() {
      val content = Random.Default.nextBytes(10)
      val fileId = storeFile(content = content)

      every { fileStore.read(URI("1")) } returns SizedInputStream(content.inputStream(), 10L)

      val inputStream = service.readFile(reportId, fileId)
      assertArrayEquals(content, inputStream.readAllBytes(), "File content")
    }

    @Test
    fun `throws exception if file is on a different report`() {
      val otherReportId = insertReport(year = 1990)
      val fileId = storeFile()

      assertThrows<FileNotFoundException> { service.readFile(otherReportId, fileId) }
    }

    @Test
    fun `throws exception if no permission to read report`() {
      val fileId = storeFile()

      every { user.canReadReport(any()) } returns false

      assertThrows<ReportNotFoundException> { service.readFile(reportId, fileId) }
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
  inner class StoreFile {
    @Test
    fun `associates file with report`() {
      val fileId = storeFile()

      assertEquals(listOf(ReportFilesRow(fileId, reportId)), reportFilesDao.findAll())
    }

    @Test
    fun `throws exception if no permission to update report`() {
      every { user.canUpdateReport(any()) } returns false

      assertThrows<AccessDeniedException> { storeFile() }
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

      service.updatePhoto(
          ReportPhotoModel(
              newCaption,
              ExistingFileMetadata(MediaType.IMAGE_JPEG_VALUE, "upload.jpg", fileId, 0, URI("/")),
              reportId))

      val row = reportPhotosDao.fetchOneByFileId(fileId)

      assertEquals(newCaption, row?.caption)
    }

    @Test
    fun `throws exception if no permission to update report`() {
      val fileId = storePhoto()

      every { user.canUpdateReport(any()) } returns false

      assertThrows<AccessDeniedException> {
        service.updatePhoto(
            ReportPhotoModel(
                "caption",
                ExistingFileMetadata(MediaType.IMAGE_JPEG_VALUE, "upload.jpg", fileId, 0, URI("/")),
                reportId))
      }
    }
  }

  private fun storeFile(
      reportId: ReportId = this.reportId,
      content: ByteArray = ByteArray(0),
      contentType: String = excelContentType,
      filename: String = "file.xls",
  ): FileId {
    return service.storeFile(
        reportId,
        content.inputStream(),
        FileMetadata.of(contentType, filename, content.size.toLong()))
  }

  private fun storePhoto(
      reportId: ReportId = this.reportId,
      content: ByteArray = ByteArray(0),
      contentType: String = MediaType.IMAGE_JPEG_VALUE,
      filename: String = "upload.jpg",
  ): FileId {
    return service.storePhoto(
        reportId,
        content.inputStream(),
        FileMetadata.of(contentType, filename, content.size.toLong()))
  }
}
