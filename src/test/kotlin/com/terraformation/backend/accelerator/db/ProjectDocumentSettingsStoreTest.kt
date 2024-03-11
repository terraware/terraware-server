package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.ExistingProjectDocumentSettingsModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.mockUser
import io.mockk.every
import java.net.URI
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProjectDocumentSettingsStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: ProjectDocumentSettingsStore by lazy {
    ProjectDocumentSettingsStore(dslContext)
  }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()

    every { user.canReadProjectDocumentSettings(any()) } returns true
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `gets the submission document`() {
      val projectId = insertProject()
      insertProjectDocumentSettings()

      assertEquals(
          ExistingProjectDocumentSettingsModel(
              dropboxFolderPath = "/terraware-uploads",
              fileNaming = "FILE_NAMING",
              googleFolderUrl = URI("https://drive.google.com/drive/folders/FAKEhYWOWJ-l6ZI"),
              projectId = projectId),
          store.fetchOneById(projectId))
    }

    @Test
    fun `throws exception if no permission to read submission document`() {
      val projectId = insertProject()
      insertProjectDocumentSettings()

      every { user.canReadProjectDocumentSettings(any()) } returns false

      assertThrows<ProjectDocumentSettingsNotConfiguredException> { store.fetchOneById(projectId) }
    }
  }
}
