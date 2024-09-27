package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.URI
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class GoogleFolderUpdaterTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private val eventPublisher = TestEventPublisher()
  private val googleDriveWriter: GoogleDriveWriter = mockk()

  private val updater: GoogleFolderUpdater by lazy {
    GoogleFolderUpdater(
        ApplicationStore(
            clock,
            countriesDao,
            CountryDetector(),
            dslContext,
            eventPublisher,
            Messages(),
            organizationsDao,
        ),
        config,
        dslContext,
        googleDriveWriter,
        ProjectAcceleratorDetailsStore(clock, dslContext, eventPublisher),
        SystemUser(usersDao))
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
    projectId = insertProject()
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
  }

  @Nested
  inner class ApplicationInternalNameUpdated {
    @Test
    fun `Updates Google folder name if drive url exists, but project file naming does not`() {
      insertProjectAcceleratorDetails(projectId = projectId, googleFolderUrl = oldFolderUrl)

      updater.on(ApplicationInternalNameUpdatedEvent(applicationId))

      verify(exactly = 1) {
        googleDriveWriter.renameFile(oldFolderId, "XXX_Organization$INTERNAL_FOLDER_SUFFIX")
      }
      verify(exactly = 0) { googleDriveWriter.findOrCreateFolders(any(), any(), any()) }
    }

    @Test
    fun `Creates Google folder name if drive url does not exist`() {
      updater.on(ApplicationInternalNameUpdatedEvent(applicationId))

      verify(exactly = 1) {
        googleDriveWriter.findOrCreateFolders(
            driveId, parentFolderId, listOf("XXX_Organization$INTERNAL_FOLDER_SUFFIX"))
      }
      verify(exactly = 0) { googleDriveWriter.renameFile(any(), any()) }
    }

    @MethodSource(
        "com.terraformation.backend.accelerator.GoogleFolderUpdaterTest#applicationStatues")
    @ParameterizedTest
    fun `Does not update Google folder name if application is not in Pre-screen`(
        status: ApplicationStatus
    ) {
      val existing = applicationsDao.fetchOneById(applicationId)!!
      applicationsDao.update(existing.copy(applicationStatusId = status))

      updater.on(ApplicationInternalNameUpdatedEvent(applicationId))

      if (status == ApplicationStatus.NotSubmitted || status == ApplicationStatus.FailedPreScreen) {
        googleDriveWriter.renameFile("fileId", "XXX_Organization$INTERNAL_FOLDER_SUFFIX")
      } else {
        verify(exactly = 0) { googleDriveWriter.renameFile(any(), any()) }
      }
    }
  }

  companion object {
    @JvmStatic fun applicationStatues() = ApplicationStatus.entries.map { Arguments.of(it) }
  }
}
