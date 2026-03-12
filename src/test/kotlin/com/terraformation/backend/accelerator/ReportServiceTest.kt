package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.accelerator.model.PublishedReportComparedProps
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.tables.records.ReportPhotosRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportsRecord
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
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
import com.terraformation.backend.funder.db.PublishedReportStore
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.tracking.db.ObservationResultsStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

class ReportServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val messages = Messages()
  private val eventPublisher = TestEventPublisher()
  private val fileService = mockk<FileService>()
  private val reportStore: ReportStore by lazy {
    ReportStore(
        clock,
        dslContext,
        eventPublisher,
        messages,
        ObservationResultsStore(dslContext),
        reportsDao,
        SystemUser(usersDao),
    )
  }
  private val publishedReportStore: PublishedReportStore by lazy {
    PublishedReportStore(dslContext)
  }

  private val service: ReportService by lazy {
    ReportService(
        dslContext,
        eventPublisher,
        fileService,
        reportPhotosDao,
        reportStore,
        publishedReportPhotosDao,
        publishedReportStore,
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
    organizationId = insertOrganization(timeZone = ZoneOffset.UTC)
    projectId = insertProject()
    insertProjectReportConfig()
    reportId =
        insertReport(
            status = ReportStatus.Approved,
            highlights = "highlights",
            additionalComments = "additional comments",
            financialSummaries = "financial summaries",
        )

    every { fileService.storeFile(any(), any(), any(), any(), any()) } answers
        {
          val func = arg<((storedFile: FileService.StoredFile) -> Unit)?>(4)
          val fileId = insertFile()
          func?.let { it(FileService.StoredFile(fileId, arg(2))) }
          fileId
        }

    every { fileService.readFile(any()) } returns inputStream
  }

  @Nested
  inner class Daily {
    @Test
    fun `notifies upcoming reports daily`() {
      with(REPORTS) {
        dslContext
            .update(this)
            .set(STATUS_ID, ReportStatus.NotSubmitted)
            .setNull(SUBMITTED_BY)
            .setNull(SUBMITTED_TIME)
            .execute()
      }
      service.on(DailyTaskTimeArrivedEvent())
      assertIsEventListener<DailyTaskTimeArrivedEvent>(service)
      assertTableEquals(
          listOf(
              ReportsRecord(
                  configId = inserted.projectReportConfigId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.EPOCH,
                  endDate = LocalDate.EPOCH.plusDays(1),
                  createdBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant(),
                  highlights = "highlights",
                  reportQuarterId = ReportQuarter.Q1,
                  upcomingNotificationSentTime = clock.instant(),
                  financialSummaries = "financial summaries",
                  additionalComments = "additional comments",
              )
          ),
          "Report was updated with notification sent",
      )
    }
  }

  @Nested
  inner class PublishReport {
    @Test
    fun `publishes report and deletes photos marked as deleted`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      val existingFileId = insertFile()
      insertReportPhoto()
      insertPublishedReport()
      insertPublishedReportPhoto()

      insertFile()
      insertReportPhoto(deleted = true)
      insertPublishedReportPhoto()

      service.publishReport(reportId)

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
      insertPublishedReport()
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

  @Nested
  inner class UnpublishedProperties {
    @BeforeEach
    fun setUp() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
    }

    @Test
    fun `returns empty list when report has never been published`() {
      assertEquals(
          emptyList<PublishedReportComparedProps>(),
          service.fetchOne(reportId, computeUnpublishedChanges = true).unpublishedProperties,
          "Report has not been published",
      )
    }

    @Test
    fun `returns empty list when computeUnpublishedChanges is false`() {
      assertEquals(
          emptyList<PublishedReportComparedProps>(),
          service.fetchOne(reportId, computeUnpublishedChanges = false).unpublishedProperties,
          "Not requesting unpublished properties",
      )
    }

    @Test
    fun `returns empty list when user doesn't have permission to view published report`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertOrganizationUser(role = Role.Manager)

      assertEquals(
          emptyList<PublishedReportComparedProps>(),
          service.fetchOne(reportId, computeUnpublishedChanges = true).unpublishedProperties,
          "User can't read published reports",
      )
    }

    @Test
    fun `returns empty list when report matches published qualitative properties`() {
      insertPublishedReport(
          highlights = "highlights",
          additionalComments = "additional comments",
          financialSummaries = "financial summaries",
      )
      insertReportAchievement(position = 0, achievement = "Achievement A")
      insertPublishedReportAchievement(position = 0, achievement = "Achievement A")
      insertReportChallenge(position = 0, challenge = "Challenge A", mitigationPlan = "Plan A")
      insertPublishedReportChallenge(
          position = 0,
          challenge = "Challenge A",
          mitigationPlan = "Plan A",
      )

      assertEquals(
          emptyList<PublishedReportComparedProps>(),
          service.fetchOne(reportId, computeUnpublishedChanges = true).unpublishedProperties,
          "unpublishedProperties should be empty when nothing changed",
      )
    }

    @Test
    fun `identifies all changed properties`() {
      val commonIndicatorId = insertCommonIndicator(isPublishable = true)
      val projectIndicatorId = insertProjectIndicator(isPublishable = true)

      // Published version: old values for all properties
      insertPublishedReport(
          highlights = "old highlights",
          additionalComments = "old comments",
          financialSummaries = "old financial summaries",
      )
      // Published has no achievements or challenges
      insertPublishedReportCommonIndicator(
          indicatorId = commonIndicatorId,
          value = BigDecimal(10),
      )
      insertPublishedReportProjectIndicator(
          indicatorId = projectIndicatorId,
          value = BigDecimal(30),
      )
      insertPublishedReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.Seedlings,
          value = BigDecimal(50),
      )

      // Current draft: different values for all properties
      insertReportAchievement(position = 0, achievement = "New Achievement")
      insertReportChallenge(
          position = 0,
          challenge = "New Challenge",
          mitigationPlan = "New Plan",
      )
      insertReportCommonIndicator(indicatorId = commonIndicatorId, value = BigDecimal(20))
      insertReportProjectIndicator(indicatorId = projectIndicatorId, value = BigDecimal(40))
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.Seedlings,
          systemValue = BigDecimal(40),
          overrideValue = BigDecimal(60),
      )

      insertFile()
      insertReportPhoto(caption = "new caption")
      insertPublishedReportPhoto(caption = "old caption")

      assertEquals(
          setOf(
              PublishedReportComparedProps.Achievements,
              PublishedReportComparedProps.AdditionalComments,
              PublishedReportComparedProps.AutoCalculatedIndicators,
              PublishedReportComparedProps.Challenges,
              PublishedReportComparedProps.CommonIndicators,
              PublishedReportComparedProps.FinancialSummaries,
              PublishedReportComparedProps.Highlights,
              PublishedReportComparedProps.Photos,
              PublishedReportComparedProps.ProjectIndicators,
          ),
          service
              .fetchOne(reportId, includeIndicators = true, computeUnpublishedChanges = true)
              .unpublishedProperties
              .toSet(),
          "Unpublished properties should contain all props",
      )
    }

    @Test
    fun `identifies photos as changed when new photo added`() {
      insertFile()
      insertPublishedReport(
          highlights = "highlights",
          additionalComments = "additional comments",
          financialSummaries = "financial summaries",
      )
      // Current report has photo; published has none
      insertReportPhoto()

      assertEquals(
          listOf(PublishedReportComparedProps.Photos),
          service.fetchOne(reportId, computeUnpublishedChanges = true).unpublishedProperties,
          "Adding a photo causes unpublishedProperties to include photos",
      )
    }

    @Test
    fun `identifies photos as changed when photo removed`() {
      insertFile()
      insertPublishedReport(
          highlights = "highlights",
          additionalComments = "additional comments",
          financialSummaries = "financial summaries",
      )
      // Insert the photo as deleted in the current report to satisfy the FK on
      // published_report_photos
      insertReportPhoto(deleted = true)
      insertPublishedReportPhoto()

      assertEquals(
          listOf(PublishedReportComparedProps.Photos),
          service.fetchOne(reportId, computeUnpublishedChanges = true).unpublishedProperties,
          "Removing a photo causes unpublishedProperties to include photos",
      )
    }

    @Test
    fun `does not identify commonIndicators as changed for non-publishable indicators`() {
      insertCommonIndicator(isPublishable = false)
      insertPublishedReport(
          highlights = "highlights",
          additionalComments = "additional comments",
          financialSummaries = "financial summaries",
      )
      // Published has no common indicator row (non-publishable not published)
      insertReportCommonIndicator(value = BigDecimal(99))

      assertEquals(
          emptyList<PublishedReportComparedProps>(),
          service
              .fetchOne(reportId, includeIndicators = true, computeUnpublishedChanges = true)
              .unpublishedProperties,
          "CommonIndicators should not be flagged for non-publishable indicators",
      )
    }

    @Test
    fun `does not identify projectIndicators as changed for non-publishable indicators`() {
      insertProjectIndicator(isPublishable = false)
      insertPublishedReport(
          highlights = "highlights",
          additionalComments = "additional comments",
          financialSummaries = "financial summaries",
      )
      // Published has no project indicator row (non-publishable not published)
      insertReportProjectIndicator(value = BigDecimal(99))

      assertEquals(
          emptyList<PublishedReportComparedProps>(),
          service
              .fetchOne(reportId, includeIndicators = true, computeUnpublishedChanges = true)
              .unpublishedProperties,
          "ProjectIndicators should not be flagged for non-publishable indicators",
      )
    }

    @Test
    fun `uses system value when no override for autoCalculatedIndicators comparison`() {
      insertPublishedReport(
          highlights = "highlights",
          additionalComments = "additional comments",
          financialSummaries = "financial summaries",
      )
      // Publish Seedlings=40 only; other indicators have no published rows and no current rows.
      insertPublishedReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.Seedlings,
          value = BigDecimal(40),
      )
      // System value 40, no override — should match the published value of 40
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.Seedlings,
          systemValue = BigDecimal(40),
      )

      assertEquals(
          emptyList<PublishedReportComparedProps>(),
          service
              .fetchOne(reportId, includeIndicators = true, computeUnpublishedChanges = true)
              .unpublishedProperties,
          "AutoCalculatedIndicators should not be flagged when system value matches published",
      )
    }

    @Test
    fun `returns correct unpublishedProperties per report when fetching multiple reports`() {
      // Report 1 (reportId from setUp): never published → unpublishedProperties == emptyList()

      // Report 2: published and unpublished matches exactly → unpublishedProperties == emptyList()
      val reportId2 =
          insertReport(
              startDate = LocalDate.of(2030, Month.JANUARY, 1),
              endDate = LocalDate.of(2030, Month.MARCH, 31),
              highlights = "same highlights",
          )
      insertPublishedReport(highlights = "same highlights")

      // Report 3: published but highlights differs → unpublishedProperties contains Highlights
      val reportId3 =
          insertReport(
              startDate = LocalDate.of(2030, Month.APRIL, 1),
              endDate = LocalDate.of(2030, Month.JUNE, 30),
              highlights = "new highlights",
          )
      insertPublishedReport(highlights = "old highlights")

      val reports =
          service.fetch(
              projectId = projectId,
              includeFuture = true,
              includeIndicators = true,
              computeUnpublishedChanges = true,
          )
      val byId = reports.associateBy { it.id }

      assertEquals(
          emptyList<PublishedReportComparedProps>(),
          byId[reportId]?.unpublishedProperties,
          "Report 1 (never published): unpublishedProperties should be empty",
      )
      assertEquals(
          emptyList<PublishedReportComparedProps>(),
          byId[reportId2]?.unpublishedProperties,
          "Report 2 (published, no changes): unpublishedProperties should be empty",
      )
      assertEquals(
          listOf(PublishedReportComparedProps.Highlights),
          byId[reportId3]?.unpublishedProperties,
          "Report 3 (highlights changed): unpublishedProperties should just be Highlights",
      )
    }

    @Test
    fun `does not identify commonIndicators as changed when only projectsComments changed`() {
      insertCommonIndicator(isPublishable = true)
      insertPublishedReport(
          highlights = "highlights",
          additionalComments = "additional comments",
          financialSummaries = "financial summaries",
      )
      insertPublishedReportCommonIndicator(
          value = BigDecimal(10),
          projectsComments = "old projects comments",
      )
      insertReportCommonIndicator(
          value = BigDecimal(10),
          projectsComments = "new projects comments",
      )

      assertEquals(
          emptyList<PublishedReportComparedProps>(),
          service
              .fetchOne(reportId, includeIndicators = true, computeUnpublishedChanges = true)
              .unpublishedProperties,
          "CommonIndicators should not be flagged when only projectsComments changed",
      )
    }

    @Test
    fun `identifies commonIndicators as changed when progressNotes changed`() {
      insertCommonIndicator(isPublishable = true)
      insertPublishedReport(
          highlights = "highlights",
          additionalComments = "additional comments",
          financialSummaries = "financial summaries",
      )
      insertPublishedReportCommonIndicator(value = BigDecimal(10), progressNotes = "old notes")
      insertReportCommonIndicator(value = BigDecimal(10), progressNotes = "new notes")

      assertEquals(
          listOf(PublishedReportComparedProps.CommonIndicators),
          service
              .fetchOne(reportId, includeIndicators = true, computeUnpublishedChanges = true)
              .unpublishedProperties,
          "CommonIndicators should be flagged when progressNotes changed",
      )
    }

    @Test
    fun `identifies projectIndicators as changed when progressNotes changed`() {
      insertProjectIndicator(isPublishable = true)
      insertPublishedReport(
          highlights = "highlights",
          additionalComments = "additional comments",
          financialSummaries = "financial summaries",
      )
      insertPublishedReportProjectIndicator(value = BigDecimal(10), progressNotes = "old notes")
      insertReportProjectIndicator(value = BigDecimal(10), progressNotes = "new notes")

      assertEquals(
          listOf(PublishedReportComparedProps.ProjectIndicators),
          service
              .fetchOne(reportId, includeIndicators = true, computeUnpublishedChanges = true)
              .unpublishedProperties,
          "ProjectIndicators should be flagged when progressNotes changed",
      )
    }

    @Test
    fun `identifies autoCalculatedIndicators as changed when progressNotes changed`() {
      insertPublishedReport(
          highlights = "highlights",
          additionalComments = "additional comments",
          financialSummaries = "financial summaries",
      )
      insertPublishedReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.Seedlings,
          value = BigDecimal(40),
          progressNotes = "old notes",
      )
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.Seedlings,
          systemValue = BigDecimal(40),
          progressNotes = "new notes",
      )

      assertEquals(
          listOf(PublishedReportComparedProps.AutoCalculatedIndicators),
          service
              .fetchOne(reportId, includeIndicators = true, computeUnpublishedChanges = true)
              .unpublishedProperties,
          "AutoCalculatedIndicators should be flagged when progressNotes changed",
      )
    }

    //    @Test
    //    fun `does not identify commonIndicators as changed when progressNotes matches`() {
    //      insertCommonIndicator(isPublishable = true)
    //      insertPublishedReport(
    //          highlights = "highlights",
    //          additionalComments = "additional comments",
    //          financialSummaries = "financial summaries",
    //      )
    //      insertPublishedReportCommonIndicator(value = BigDecimal(10), progressNotes = "same
    // notes")
    //      insertReportCommonIndicator(value = BigDecimal(10), progressNotes = "same notes")
    //
    //      assertEquals(
    //          emptyList<PublishedReportComparedProps>(),
    //          service
    //              .fetchOne(reportId, includeIndicators = true, computeUnpublishedChanges = true)
    //              .unpublishedProperties,
    //          "CommonIndicators should not be flagged when progressNotes matches",
    //      )
    //    }
    //
    //    @Test
    //    fun `does not identify projectIndicators as changed when progressNotes matches`() {
    //      insertProjectIndicator(isPublishable = true)
    //      insertPublishedReport(
    //          highlights = "highlights",
    //          additionalComments = "additional comments",
    //          financialSummaries = "financial summaries",
    //      )
    //      insertPublishedReportProjectIndicator(value = BigDecimal(10), progressNotes = "same
    // notes")
    //      insertReportProjectIndicator(value = BigDecimal(10), progressNotes = "same notes")
    //
    //      assertEquals(
    //          emptyList<PublishedReportComparedProps>(),
    //          service
    //              .fetchOne(reportId, includeIndicators = true, computeUnpublishedChanges = true)
    //              .unpublishedProperties,
    //          "ProjectIndicators should not be flagged when progressNotes matches",
    //      )
    //    }
    //
    //    @Test
    //    fun `does not identify autoCalculatedIndicators as changed when progressNotes matches`() {
    //      insertPublishedReport(
    //          highlights = "highlights",
    //          additionalComments = "additional comments",
    //          financialSummaries = "financial summaries",
    //      )
    //      insertPublishedReportAutoCalculatedIndicator(
    //          indicator = AutoCalculatedIndicator.Seedlings,
    //          value = BigDecimal(40),
    //          progressNotes = "same notes",
    //      )
    //      insertReportAutoCalculatedIndicator(
    //          indicator = AutoCalculatedIndicator.Seedlings,
    //          systemValue = BigDecimal(40),
    //          progressNotes = "same notes",
    //      )
    //
    //      assertEquals(
    //          emptyList<PublishedReportComparedProps>(),
    //          service
    //              .fetchOne(reportId, includeIndicators = true, computeUnpublishedChanges = true)
    //              .unpublishedProperties,
    //          "AutoCalculatedIndicators should not be flagged when progressNotes matches",
    //      )
    //    }
  }
}
