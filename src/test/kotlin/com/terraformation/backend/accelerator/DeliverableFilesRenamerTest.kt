package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.util.toInstant
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class DeliverableFilesRenamerTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private val eventPublisher = TestEventPublisher()
  private val googleDriveWriter: GoogleDriveWriter = mockk()
  private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService = mockk()

  private val renamer: DeliverableFilesRenamer by lazy {
    DeliverableFilesRenamer(
        ApplicationStore(
            clock,
            countriesDao,
            TestSingletons.countryDetector,
            dslContext,
            eventPublisher,
            Messages(),
        ),
        config,
        DeliverableStore(dslContext),
        dslContext,
        googleDriveWriter,
        projectAcceleratorDetailsService,
        submissionDocumentsDao,
        SystemUser(usersDao),
    )
  }

  private lateinit var projectId: ProjectId
  private lateinit var applicationId: ApplicationId

  private val driveId: String = "drive"
  private val parentFolderId = "parent"
  private val newFolderId = "newFolder"
  private val newFolderUrl = URI("https://drive.google.com/drive/$newFolderId")
  private val oldFolderId = "oldFolder"
  private val oldFolderUrl = URI("https://drive.google.com/drive/$oldFolderId")

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertCohort()
    projectId = insertProject(cohortId = inserted.cohortId)
    applicationId = insertApplication(internalName = "XXX_Organization")

    every { config.accelerator } returns
        TerrawareServerConfig.AcceleratorConfig(applicationGoogleFolderId = parentFolderId)
    every { googleDriveWriter.findOrCreateFolders(driveId, parentFolderId, any()) } returns
        newFolderId
    every { googleDriveWriter.getDriveIdForFile(any()) } returns driveId

    every { googleDriveWriter.getFileIdForFolderUrl(newFolderUrl) } returns newFolderId
    every { googleDriveWriter.shareFile(newFolderId) } returns newFolderUrl

    every { googleDriveWriter.getFileIdForFolderUrl(oldFolderUrl) } returns oldFolderId
    every { googleDriveWriter.shareFile(oldFolderId) } returns oldFolderUrl

    every { googleDriveWriter.renameFile(any(), any()) } returns Unit
    every { googleDriveWriter.moveFile(any(), any()) } returns Unit

    every { user.canUpdateProjectDocumentSettings(any()) } returns true
  }

  @Nested
  inner class ApplicationInternalNameUpdated {
    @Test
    fun `updates Google folder name if drive url exists, but project file naming does not`() {
      every { projectAcceleratorDetailsService.fetchOneById(projectId) } returns
          ProjectAcceleratorDetailsModel(projectId = projectId, googleFolderUrl = oldFolderUrl)

      renamer.on(ApplicationInternalNameUpdatedEvent(applicationId))

      verify(exactly = 1) {
        googleDriveWriter.renameFile(oldFolderId, "XXX_Organization$INTERNAL_FOLDER_SUFFIX")
      }
      verify(exactly = 0) { googleDriveWriter.findOrCreateFolders(any(), any(), any()) }
    }

    @Test
    fun `creates Google folder name if drive url does not exist`() {
      every { projectAcceleratorDetailsService.fetchOneById(projectId) } returns
          ProjectAcceleratorDetailsModel(projectId = projectId, googleFolderUrl = null)

      renamer.on(ApplicationInternalNameUpdatedEvent(applicationId))

      verify(exactly = 1) {
        googleDriveWriter.findOrCreateFolders(
            driveId,
            parentFolderId,
            listOf("XXX_Organization$INTERNAL_FOLDER_SUFFIX"),
        )
      }
      verify(exactly = 0) { googleDriveWriter.renameFile(any(), any()) }
    }

    @Test
    fun `renames project document submissions and moves them to the folder`() {
      // Application Modules
      insertModule(phase = CohortPhase.Application)
      insertApplicationModule()
      insertDeliverable(
          name = "Application Documents",
          deliverableTypeId = DeliverableType.Document,
      )
      insertSubmission()
      val applicationUploadDate = LocalDate.of(2024, 6, 1)
      val applicationFileId = "applicationDocumentId"

      val applicationDocument =
          insertSubmissionDocument(
              createdTime = applicationUploadDate.toInstant(ZoneId.of("UTC"), LocalTime.MIDNIGHT),
              description = "Application submission",
              location = applicationFileId,
              originalName = "file.txt",
          )
      val existingApplicationDocument = submissionDocumentsDao.fetchOneById(applicationDocument)!!

      // Participant Modules
      insertModule()
      insertProjectModule()
      insertDeliverable(name = "Test Documents", deliverableTypeId = DeliverableType.Document)
      insertSubmission()

      val uploadDate = LocalDate.of(2024, 8, 1)
      val fileId1 = "documentId1"
      val fileId2 = "documentId2"
      val fileId3 = "documentId3"
      val uniqueFileId = "uniqueId"
      val dropboxFileId = "dropboxId"
      val correctFileId = "correctFile"
      val maxSuffixFileId = "maxSuffixFile"

      val document1 =
          insertSubmissionDocument(
              createdTime = uploadDate.toInstant(ZoneId.of("UTC"), LocalTime.MIDNIGHT),
              description = "Same",
              location = fileId1,
              originalName = "file.txt",
          )
      val document2 =
          insertSubmissionDocument(
              createdTime = uploadDate.toInstant(ZoneId.of("UTC"), LocalTime.MIDNIGHT),
              description = "Same",
              location = fileId2,
              originalName = "file.txt",
          )
      val document3 =
          insertSubmissionDocument(
              createdTime = uploadDate.toInstant(ZoneId.of("UTC"), LocalTime.MIDNIGHT),
              description = "Same",
              location = fileId3,
              originalName = "file.txt",
          )
      val uniqueDocument =
          insertSubmissionDocument(
              createdTime = uploadDate.toInstant(ZoneId.of("UTC"), LocalTime.MIDNIGHT),
              description = "Unique",
              location = uniqueFileId,
              originalName = "file.txt",
          )
      val dropboxDocument =
          insertSubmissionDocument(
              createdTime = uploadDate.toInstant(ZoneId.of("UTC"), LocalTime.MIDNIGHT),
              description = "Unchanged",
              documentStore = DocumentStore.Dropbox,
              location = dropboxFileId,
              originalName = "file.txt",
          )
      val correctDocument =
          insertSubmissionDocument(
              createdTime = uploadDate.toInstant(ZoneId.of("UTC"), LocalTime.MIDNIGHT),
              description = "Description",
              location = correctFileId,
              name = "Test Documents_2024-08-01_XXX_Organization_Description_50.txt",
              originalName = "file.txt",
          )
      val maxSuffixDocument =
          insertSubmissionDocument(
              createdTime = uploadDate.toInstant(ZoneId.of("UTC"), LocalTime.MIDNIGHT),
              description = "Description",
              location = maxSuffixFileId,
              originalName = "file.txt",
          )

      val existingDocument1 = submissionDocumentsDao.fetchOneById(document1)!!
      val existingDocument2 = submissionDocumentsDao.fetchOneById(document2)!!
      val existingDocument3 = submissionDocumentsDao.fetchOneById(document3)!!
      val existingUniqueDocument = submissionDocumentsDao.fetchOneById(uniqueDocument)!!
      val existingDropboxDocument = submissionDocumentsDao.fetchOneById(dropboxDocument)!!
      val existingCorrectDocument = submissionDocumentsDao.fetchOneById(correctDocument)!!
      val existingMaxSuffixDocument = submissionDocumentsDao.fetchOneById(maxSuffixDocument)!!

      every { projectAcceleratorDetailsService.fetchOneById(projectId) } returns
          ProjectAcceleratorDetailsModel(projectId = projectId, googleFolderUrl = oldFolderUrl)

      renamer.on(ApplicationInternalNameUpdatedEvent(applicationId))

      verify(exactly = 1) {
        googleDriveWriter.renameFile(oldFolderId, "XXX_Organization$INTERNAL_FOLDER_SUFFIX")
      }
      verify(exactly = 1) {
        googleDriveWriter.renameFile(
            applicationFileId,
            "Application Documents_2024-06-01_XXX_Organization_Application submission.txt",
        )
      }
      verify(exactly = 1) {
        googleDriveWriter.renameFile(fileId1, "Test Documents_2024-08-01_XXX_Organization_Same.txt")
      }
      verify(exactly = 1) {
        googleDriveWriter.renameFile(
            fileId2,
            "Test Documents_2024-08-01_XXX_Organization_Same_2.txt",
        )
      }
      verify(exactly = 1) {
        googleDriveWriter.renameFile(
            fileId3,
            "Test Documents_2024-08-01_XXX_Organization_Same_3.txt",
        )
      }
      verify(exactly = 1) {
        googleDriveWriter.renameFile(
            uniqueFileId,
            "Test Documents_2024-08-01_XXX_Organization_Unique.txt",
        )
      }
      verify(exactly = 0) { googleDriveWriter.renameFile(dropboxFileId, any()) }
      verify(exactly = 0) { googleDriveWriter.renameFile(correctFileId, any()) }
      verify(exactly = 1) {
        googleDriveWriter.renameFile(
            maxSuffixFileId,
            "Test Documents_2024-08-01_XXX_Organization_Description_51.txt",
        )
      }

      verify(exactly = 1) { googleDriveWriter.moveFile(applicationFileId, oldFolderId) }
      verify(exactly = 1) { googleDriveWriter.moveFile(fileId1, oldFolderId) }
      verify(exactly = 1) { googleDriveWriter.moveFile(fileId2, oldFolderId) }
      verify(exactly = 1) { googleDriveWriter.moveFile(fileId3, oldFolderId) }
      verify(exactly = 1) { googleDriveWriter.moveFile(uniqueFileId, oldFolderId) }
      verify(exactly = 0) { googleDriveWriter.moveFile(dropboxFileId, any()) }
      verify(exactly = 1) { googleDriveWriter.moveFile(correctFileId, oldFolderId) }
      verify(exactly = 1) { googleDriveWriter.moveFile(maxSuffixFileId, oldFolderId) }

      assertEquals(
          existingApplicationDocument.copy(
              name = "Application Documents_2024-06-01_XXX_Organization_Application submission.txt"
          ),
          submissionDocumentsDao.fetchOneById(applicationDocument),
          "Application document after rename.",
      )
      assertEquals(
          existingDocument1.copy(name = "Test Documents_2024-08-01_XXX_Organization_Same.txt"),
          submissionDocumentsDao.fetchOneById(document1),
          "Document 1 after rename.",
      )
      assertEquals(
          existingDocument2.copy(name = "Test Documents_2024-08-01_XXX_Organization_Same_2.txt"),
          submissionDocumentsDao.fetchOneById(document2),
          "Document 2 after rename has a suffix.",
      )
      assertEquals(
          existingDocument3.copy(name = "Test Documents_2024-08-01_XXX_Organization_Same_3.txt"),
          submissionDocumentsDao.fetchOneById(document3),
          "Document 3 after rename has a suffix.",
      )
      assertEquals(
          existingUniqueDocument.copy(
              name = "Test Documents_2024-08-01_XXX_Organization_Unique.txt"
          ),
          submissionDocumentsDao.fetchOneById(uniqueDocument),
          "Unique document name does not have a suffix.",
      )
      assertEquals(
          existingDropboxDocument,
          submissionDocumentsDao.fetchOneById(dropboxDocument),
          "Dropbox document is unchanged.",
      )
      assertEquals(
          existingCorrectDocument,
          submissionDocumentsDao.fetchOneById(correctDocument),
          "Document with correct naming scheme is unchanged.",
      )
      assertEquals(
          existingMaxSuffixDocument.copy(
              name = "Test Documents_2024-08-01_XXX_Organization_Description_51.txt"
          ),
          submissionDocumentsDao.fetchOneById(maxSuffixDocument),
          "Document will add one to existing max suffix on naming scheme conflict",
      )
    }

    @MethodSource(
        "com.terraformation.backend.accelerator.DeliverableFilesRenamerTest#applicationStatues"
    )
    @ParameterizedTest
    fun `Does not update Google folder name if application is not in Pre-screen`(
        status: ApplicationStatus
    ) {
      every { projectAcceleratorDetailsService.fetchOneById(projectId) } returns
          ProjectAcceleratorDetailsModel(projectId = projectId, googleFolderUrl = oldFolderUrl)

      val existing = applicationsDao.fetchOneById(applicationId)!!
      applicationsDao.update(existing.copy(applicationStatusId = status))

      renamer.on(ApplicationInternalNameUpdatedEvent(applicationId))

      if (status == ApplicationStatus.NotSubmitted || status == ApplicationStatus.FailedPreScreen) {
        verify(exactly = 1) {
          googleDriveWriter.renameFile(oldFolderId, "XXX_Organization$INTERNAL_FOLDER_SUFFIX")
        }
      } else {
        verify(exactly = 0) { googleDriveWriter.renameFile(any(), any()) }
      }
    }
  }

  companion object {
    @JvmStatic fun applicationStatues() = ApplicationStatus.entries.map { Arguments.of(it) }
  }
}
