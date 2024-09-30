package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ApplicationId
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

class GoogleFolderUpdaterTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
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
        googleDriveWriter,
        ProjectAcceleratorDetailsStore(clock, dslContext, eventPublisher),
        SystemUser(usersDao))
  }

  private lateinit var projectId: ProjectId
  private lateinit var applicationId: ApplicationId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    projectId = insertProject()
    applicationId = insertApplication(internalName = "XXX_Organization")

    every { googleDriveWriter.getFileIdForFolderUrl(any()) } returns "fileId"
    every { googleDriveWriter.renameFile("fileId", any()) } returns Unit
  }

  @Nested
  inner class ApplicationInternalNameUpdated {
    @Test
    fun `Updates Google folder name if drive url exists, but project file naming does not`() {
      insertProjectAcceleratorDetails(
          projectId = projectId, googleFolderUrl = URI.create("https://terraformation.com"))

      updater.on(ApplicationInternalNameUpdatedEvent(applicationId))

      verify(exactly = 1) {
        googleDriveWriter.renameFile("fileId", "XXX_Organization$INTERNAL_FOLDER_SUFFIX")
      }
    }

    @Test
    fun `Does not update Google folder name if project file naming exists`() {
      insertProjectAcceleratorDetails(
          projectId = projectId,
          googleFolderUrl = URI.create("https://terraformation.com"),
          fileNaming = "Existing file naming")

      updater.on(ApplicationInternalNameUpdatedEvent(applicationId))

      verify(exactly = 0) { googleDriveWriter.renameFile(any(), any()) }
    }

    @Test
    fun `Does not update Google folder name if drive url does not exist`() {
      updater.on(ApplicationInternalNameUpdatedEvent(applicationId))

      verify(exactly = 0) { googleDriveWriter.renameFile(any(), any()) }
    }
  }
}
