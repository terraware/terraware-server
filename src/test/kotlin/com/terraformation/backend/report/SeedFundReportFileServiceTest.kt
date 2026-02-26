package com.terraformation.backend.report

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.SeedFundReportNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.tables.pojos.SeedFundReportFilesRow
import com.terraformation.backend.db.default_schema.tables.pojos.SeedFundReportPhotosRow
import com.terraformation.backend.db.default_schema.tables.references.SEED_FUND_REPORT_FILES
import com.terraformation.backend.db.default_schema.tables.references.SEED_FUND_REPORT_PHOTOS
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.model.ExistingFileMetadata
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
import com.terraformation.backend.report.db.SeedFundReportStore
import com.terraformation.backend.report.event.SeedFundReportDeletionStartedEvent
import com.terraformation.backend.report.model.SeedFundReportFileModel
import com.terraformation.backend.report.model.SeedFundReportPhotoModel
import io.mockk.Runs
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.just
import io.mockk.mockk
import java.net.URI
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import tools.jackson.module.kotlin.jacksonObjectMapper

class SeedFundReportFileServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val fileStore: FileStore = mockk()
  private val fileService: FileService by lazy {
    FileService(dslContext, clock, eventPublisher, filesDao, fileStore)
  }
  private val seedFundReportStore: SeedFundReportStore by lazy {
    SeedFundReportStore(
        clock,
        dslContext,
        eventPublisher,
        facilitiesDao,
        jacksonObjectMapper(),
        ParentStore(dslContext),
        projectsDao,
        seedFundReportsDao,
    )
  }
  private val thumbnailService: ThumbnailService = mockk()
  private val service: SeedFundReportFileService by lazy {
    SeedFundReportFileService(
        eventPublisher,
        filesDao,
        fileService,
        seedFundReportStore,
        seedFundReportFilesDao,
        seedFundReportPhotosDao,
        thumbnailService,
    )
  }

  private val excelContentType = "application/vnd.ms-excel"
  private lateinit var seedFundReportId: SeedFundReportId
  private var storageUrlCount = 0

  @BeforeEach
  fun setUp() {
    insertOrganization()
    seedFundReportId = insertSeedFundReport()

    every { fileStore.delete(any()) } just Runs
    every { fileStore.newUrl(any(), any(), any()) } answers { URI("${++storageUrlCount}") }
    every { fileStore.write(any(), any()) } just Runs
    every { user.canReadSeedFundReport(any()) } returns true
    every { user.canUpdateSeedFundReport(any()) } returns true
  }

  @Nested
  inner class DeleteReport {
    @Test
    fun `report deletion triggers file and photo deletion`() {
      excludeRecords { fileStore.newUrl(any(), any(), any()) }
      excludeRecords { fileStore.write(any(), any()) }

      val fileId1 = storeFile(filename = "a.txt")
      val fileId2 = storeFile(filename = "b.txt")
      val photoId1 = storePhoto()
      val photoId2 = storePhoto()

      service.on(SeedFundReportDeletionStartedEvent(seedFundReportId))

      eventPublisher.assertEventsPublished(
          setOf(
              FileReferenceDeletedEvent(fileId1),
              FileReferenceDeletedEvent(fileId2),
              FileReferenceDeletedEvent(photoId1),
              FileReferenceDeletedEvent(photoId2),
          )
      )
      assertTableEmpty(SEED_FUND_REPORT_PHOTOS)
      assertTableEmpty(SEED_FUND_REPORT_FILES)

      assertIsEventListener<SeedFundReportDeletionStartedEvent>(service)
    }
  }

  @Nested
  inner class ListFiles {
    @Test
    fun `returns files with filenames`() {
      val fileId1 = storeFile(filename = "file1.xls")
      val fileId2 = storeFile(filename = "file2.xls", content = byteArrayOf(1, 2, 3))
      // Shouldn't include photos from other reports
      storeFile(insertSeedFundReport(year = 1990), filename = "file3.xls")

      val expected =
          listOf(
              SeedFundReportFileModel(
                  ExistingFileMetadata(excelContentType, "file1.xls", fileId1, 0, URI("1")),
                  seedFundReportId,
              ),
              SeedFundReportFileModel(
                  ExistingFileMetadata(excelContentType, "file2.xls", fileId2, 3, URI("2")),
                  seedFundReportId,
              ),
          )

      val actual = service.listFiles(seedFundReportId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to read report`() {
      every { user.canReadSeedFundReport(any()) } returns false

      assertThrows<SeedFundReportNotFoundException> { service.listFiles(seedFundReportId) }
    }
  }

  @Nested
  inner class ListPhotos {
    @Test
    fun `returns photos with captions`() {
      val fileId1 = storePhoto(filename = "photo1.jpg")
      val fileId2 = storePhoto(filename = "photo2.png", contentType = MediaType.IMAGE_PNG_VALUE)
      // Shouldn't include photos from other reports
      storePhoto(insertSeedFundReport(year = 1990))

      seedFundReportPhotosDao.update(
          seedFundReportPhotosDao.fetchOneByFileId(fileId2)!!.copy(caption = "caption")
      )

      val expected =
          listOf(
              SeedFundReportPhotoModel(
                  null,
                  ExistingFileMetadata(
                      MediaType.IMAGE_JPEG_VALUE,
                      "photo1.jpg",
                      fileId1,
                      0,
                      URI("1"),
                  ),
                  seedFundReportId,
              ),
              SeedFundReportPhotoModel(
                  "caption",
                  ExistingFileMetadata(
                      MediaType.IMAGE_PNG_VALUE,
                      "photo2.png",
                      fileId2,
                      0,
                      URI("2"),
                  ),
                  seedFundReportId,
              ),
          )

      val actual = service.listPhotos(seedFundReportId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to read report`() {
      every { user.canReadSeedFundReport(any()) } returns false

      assertThrows<SeedFundReportNotFoundException> { service.listPhotos(seedFundReportId) }
    }
  }

  @Nested
  inner class ReadFile {
    @Test
    fun `returns file data`() {
      val content = Random.Default.nextBytes(10)
      val fileId = storeFile(content = content)

      every { fileStore.read(URI("1")) } returns SizedInputStream(content.inputStream(), 10L)

      val inputStream = service.readFile(seedFundReportId, fileId)
      assertArrayEquals(content, inputStream.readAllBytes(), "File content")
    }

    @Test
    fun `throws exception if file is on a different report`() {
      val otherSeedFundReportId = insertSeedFundReport(year = 1990)
      val fileId = storeFile()

      assertThrows<FileNotFoundException> { service.readFile(otherSeedFundReportId, fileId) }
    }

    @Test
    fun `throws exception if no permission to read report`() {
      val fileId = storeFile()

      every { user.canReadSeedFundReport(any()) } returns false

      assertThrows<SeedFundReportNotFoundException> { service.readFile(seedFundReportId, fileId) }
    }
  }

  @Nested
  inner class ReadPhoto {
    @Test
    fun `returns photo data`() {
      val content = Random.nextBytes(10)
      val fileId = storePhoto(content = content)

      every { thumbnailService.readFile(fileId) } returns
          SizedInputStream(content.inputStream(), 10L)

      val inputStream = service.readPhoto(seedFundReportId, fileId)
      assertArrayEquals(content, inputStream.readAllBytes(), "File content")
    }

    @Test
    fun `returns thumbnail data`() {
      val content = Random.nextBytes(10)
      val fileId = storePhoto()
      val maxWidth = 10
      val maxHeight = 20

      every { thumbnailService.readFile(fileId, maxWidth, maxHeight) } returns
          SizedInputStream(content.inputStream(), 10L)

      val inputStream = service.readPhoto(seedFundReportId, fileId, maxWidth, maxHeight)
      assertArrayEquals(content, inputStream.readAllBytes(), "Thumbnail content")
    }

    @Test
    fun `throws exception if photo is on a different report`() {
      val otherSeedFundReportId = insertSeedFundReport(year = 1990)
      val fileId = storePhoto()

      assertThrows<FileNotFoundException> { service.readPhoto(otherSeedFundReportId, fileId) }
    }

    @Test
    fun `throws exception if no permission to read report`() {
      val fileId = storePhoto()

      every { user.canReadSeedFundReport(any()) } returns false

      assertThrows<SeedFundReportNotFoundException> { service.readPhoto(seedFundReportId, fileId) }
    }
  }

  @Nested
  inner class StoreFile {
    @Test
    fun `associates file with report`() {
      val fileId = storeFile()

      assertEquals(
          listOf(SeedFundReportFilesRow(fileId, seedFundReportId)),
          seedFundReportFilesDao.findAll(),
      )
    }

    @Test
    fun `throws exception if no permission to update report`() {
      every { user.canUpdateSeedFundReport(any()) } returns false

      assertThrows<AccessDeniedException> { storeFile() }
    }
  }

  @Nested
  inner class StorePhoto {
    @Test
    fun `associates photo with report`() {
      val fileId = storePhoto()

      assertEquals(
          listOf(SeedFundReportPhotosRow(seedFundReportId, fileId)),
          seedFundReportPhotosDao.findAll(),
      )
    }

    @Test
    fun `throws exception if no permission to update report`() {
      every { user.canUpdateSeedFundReport(any()) } returns false

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
          SeedFundReportPhotoModel(
              newCaption,
              ExistingFileMetadata(MediaType.IMAGE_JPEG_VALUE, "upload.jpg", fileId, 0, URI("/")),
              seedFundReportId,
          )
      )

      val row = seedFundReportPhotosDao.fetchOneByFileId(fileId)

      assertEquals(newCaption, row?.caption)
    }

    @Test
    fun `throws exception if no permission to update report`() {
      val fileId = storePhoto()

      every { user.canUpdateSeedFundReport(any()) } returns false

      assertThrows<AccessDeniedException> {
        service.updatePhoto(
            SeedFundReportPhotoModel(
                "caption",
                ExistingFileMetadata(MediaType.IMAGE_JPEG_VALUE, "upload.jpg", fileId, 0, URI("/")),
                seedFundReportId,
            )
        )
      }
    }
  }

  private fun storeFile(
      SeedFundReportId: SeedFundReportId = this.seedFundReportId,
      content: ByteArray = ByteArray(0),
      contentType: String = excelContentType,
      filename: String = "file.xls",
  ): FileId {
    return service.storeFile(
        SeedFundReportId,
        content.inputStream(),
        FileMetadata.of(contentType, filename, content.size.toLong()),
    )
  }

  private fun storePhoto(
      SeedFundReportId: SeedFundReportId = this.seedFundReportId,
      content: ByteArray = ByteArray(0),
      contentType: String = MediaType.IMAGE_JPEG_VALUE,
      filename: String = "upload.jpg",
  ): FileId {
    return service.storePhoto(
        SeedFundReportId,
        content.inputStream(),
        FileMetadata.of(contentType, filename, content.size.toLong()),
    )
  }
}
