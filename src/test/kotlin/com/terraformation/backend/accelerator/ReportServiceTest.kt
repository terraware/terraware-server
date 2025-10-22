package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.tables.records.ReportPhotosRecord
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.funder.tables.records.PublishedReportPhotosRecord
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.model.FileMetadata
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

class ReportServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val eventPublisher = TestEventPublisher()
  private val reportStore = mockk<ReportStore>()
  private val fileService = mockk<FileService>()

  private val service: ReportService by lazy {
    ReportService(
        dslContext,
        eventPublisher,
        fileService,
        reportPhotosDao,
        reportStore,
        publishedReportPhotosDao,
        SystemUser(usersDao),
        ThumbnailService(dslContext, fileService, listOf(mockk()), mockk()),
    )
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId
  private lateinit var reportId: ReportId

  private val content = byteArrayOf(1, 2, 3, 4)
  private val metadata = FileMetadata.of(MediaType.IMAGE_JPEG_VALUE, "filename", 1L)
  private val inputStream: SizedInputStream = SizedInputStream(ByteArrayInputStream(content), 1L)

  @BeforeEach
  fun setup() {
    every { reportStore.notifyUpcomingReports() } returns 1

    organizationId = insertOrganization(timeZone = ZoneOffset.UTC)
    projectId = insertProject()
    insertProjectReportConfig()
    reportId = insertReport()
    insertPublishedReport()

    every { reportStore.publishReport(any()) } returns Unit
    every { fileService.storeFile(any(), any(), any(), any(), any()) } answers
        {
          val func = arg<((fileId: FileId) -> Unit)?>(4)
          val fileId = insertFile()
          func?.let { it(fileId) }
          fileId
        }

    every { fileService.readFile(any()) } returns inputStream
  }

  @Nested
  inner class Daily {
    @Test
    fun `notifies upcoming reports daily`() {
      service.on(DailyTaskTimeArrivedEvent())
      verify(exactly = 1) { reportStore.notifyUpcomingReports() }
      assertIsEventListener<DailyTaskTimeArrivedEvent>(service)
    }
  }

  @Nested
  inner class PublishReport {
    @Test
    fun `publishes report and deletes photos marked as deleted`() {
      val existingFileId = insertFile()
      insertReportPhoto()
      insertPublishedReportPhoto()

      insertFile()
      insertReportPhoto(deleted = true)
      insertPublishedReportPhoto()

      service.publishReport(reportId)

      verify(exactly = 1) { reportStore.publishReport(reportId) }

      assertTableEquals(
          listOf(ReportPhotosRecord(reportId = reportId, fileId = existingFileId, deleted = false)),
          "Report photos",
      )

      assertTableEquals(
          listOf(PublishedReportPhotosRecord(reportId = reportId, fileId = existingFileId)),
          "Published report photos",
      )
    }
  }

  @Nested
  inner class DeleteReportPhoto {
    @Test
    fun `throws exception if no permission to update report`() {
      val fileId = insertFile()
      insertReportPhoto()

      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertThrows<AccessDeniedException>("Read Only") {
        service.deleteReportPhoto(reportId, fileId)
      }

      deleteUserGlobalRole(role = GlobalRole.ReadOnly)
      insertUserGlobalRole(role = GlobalRole.TFExpert)
      assertDoesNotThrow("TF Expert") { service.deleteReportPhoto(reportId, fileId) }

      val anotherFileId = insertFile()
      insertReportPhoto()

      deleteUserGlobalRole(role = GlobalRole.TFExpert)
      insertOrganizationUser(role = Role.Admin)
      assertDoesNotThrow("Org Admin") { service.deleteReportPhoto(reportId, anotherFileId) }
    }

    @Test
    fun `deletes photo if not published`() {
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      val existingFileId = insertFile()
      insertReportPhoto()

      val deletedFileId = insertFile()
      insertReportPhoto()

      service.deleteReportPhoto(reportId, deletedFileId)
      eventPublisher.assertEventPublished(FileReferenceDeletedEvent(deletedFileId))

      assertTableEquals(
          listOf(ReportPhotosRecord(reportId = reportId, fileId = existingFileId, deleted = false))
      )
    }

    @Test
    fun `sets photo to deleted if already published`() {
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      val existingFileId = insertFile()
      insertReportPhoto()

      val deletedFileId = insertFile()
      insertReportPhoto()
      insertPublishedReportPhoto()

      service.deleteReportPhoto(reportId, deletedFileId)
      eventPublisher.assertEventNotPublished<FileReferenceDeletedEvent>()

      assertTableEquals(
          listOf(
              ReportPhotosRecord(reportId = reportId, fileId = existingFileId, deleted = false),
              ReportPhotosRecord(reportId = reportId, fileId = deletedFileId, deleted = true),
          )
      )
    }
  }

  @Nested
  inner class ReadReportPhoto {
    @Test
    fun `throws exception if no permission to read report`() {
      val fileId = insertFile()
      insertReportPhoto()

      insertOrganizationUser(role = Role.Contributor)
      assertThrows<ReportNotFoundException>("Org contributor") {
        service.readReportPhoto(reportId, fileId)
      }

      deleteOrganizationUser()
      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertDoesNotThrow("TF Expert") { service.readReportPhoto(reportId, fileId) }

      insertOrganizationUser(role = Role.Manager)
      assertDoesNotThrow("Org Manager") { service.readReportPhoto(reportId, fileId) }
    }

    @Test
    fun `returns file stream`() {
      val fileId = insertFile()
      insertReportPhoto()

      insertOrganizationUser(role = Role.Manager)
      val inputStream = service.readReportPhoto(reportId, fileId)
      verify(exactly = 1) { fileService.readFile(fileId) }
      assertArrayEquals(content, inputStream.readAllBytes(), "Photo data")
    }
  }

  @Nested
  inner class StoreReportPhoto {
    @Test
    fun `throws exception if no permission to update report`() {
      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertThrows<AccessDeniedException>("Read Only") {
        service.storeReportPhoto(
            caption = "Test Caption",
            data = inputStream,
            metadata = metadata,
            reportId = reportId,
        )
      }

      deleteUserGlobalRole(role = GlobalRole.ReadOnly)
      insertUserGlobalRole(role = GlobalRole.TFExpert)
      assertDoesNotThrow("TF Expert") {
        service.storeReportPhoto(
            caption = "Test Caption",
            data = inputStream,
            metadata = metadata,
            reportId = reportId,
        )
      }

      deleteUserGlobalRole(role = GlobalRole.TFExpert)
      insertOrganizationUser(role = Role.Admin)
      assertDoesNotThrow("Org Admin") {
        service.storeReportPhoto(
            caption = "Test Caption",
            data = inputStream,
            metadata = metadata,
            reportId = reportId,
        )
      }
    }

    @Test
    fun `stores file and adds a report photo row`() {
      insertOrganizationUser(role = Role.Manager)
      val existingFileId = insertFile()
      insertReportPhoto()
      val fileId =
          service.storeReportPhoto(
              caption = "Test Caption",
              data = inputStream,
              metadata = metadata,
              reportId = reportId,
          )
      verify(exactly = 1) { fileService.storeFile(any(), inputStream, metadata, any(), any()) }

      assertTableEquals(
          listOf(
              ReportPhotosRecord(
                  reportId = reportId,
                  fileId = fileId,
                  caption = "Test Caption",
                  deleted = false,
              ),
              ReportPhotosRecord(reportId = reportId, fileId = existingFileId, deleted = false),
          )
      )
    }
  }

  @Nested
  inner class UpdateReportPhotoCaptions {
    @Test
    fun `throws exception if no permission to update report`() {
      val fileId = insertFile()
      insertReportPhoto(caption = "Existing Caption")

      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertThrows<AccessDeniedException>("Read Only") {
        service.updateReportPhotoCaption(
            caption = "Test Caption",
            reportId = reportId,
            fileId = fileId,
        )
      }

      deleteUserGlobalRole(role = GlobalRole.ReadOnly)
      insertUserGlobalRole(role = GlobalRole.TFExpert)
      assertDoesNotThrow("TF Expert") {
        service.updateReportPhotoCaption(
            caption = "Test Caption",
            reportId = reportId,
            fileId = fileId,
        )
      }

      deleteUserGlobalRole(role = GlobalRole.TFExpert)
      insertOrganizationUser(role = Role.Admin)
      assertDoesNotThrow("Org Admin") {
        service.updateReportPhotoCaption(
            caption = "Test Caption",
            reportId = reportId,
            fileId = fileId,
        )
      }
    }

    @Test
    fun `updates caption column`() {
      val fileId = insertFile()
      insertReportPhoto(caption = "Existing Caption")

      insertOrganizationUser(role = Role.Manager)
      service.updateReportPhotoCaption(
          caption = "New Caption",
          reportId = reportId,
          fileId = fileId,
      )

      assertTableEquals(
          listOf(
              ReportPhotosRecord(
                  reportId = reportId,
                  fileId = fileId,
                  caption = "New Caption",
                  deleted = false,
              ),
          )
      )
    }
  }
}
