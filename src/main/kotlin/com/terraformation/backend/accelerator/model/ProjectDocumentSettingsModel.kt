package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.tables.references.PROJECT_DOCUMENT_SETTINGS
import com.terraformation.backend.db.default_schema.ProjectId
import java.net.URI
import org.jooq.Record

data class ProjectDocumentSettingsModel<ID : ProjectId?>(
    val dropboxFolderPath: String,
    val fileNaming: String,
    val googleFolderUrl: URI,
    val projectId: ProjectId,
) {
  companion object {
    fun of(
        record: Record,
    ): ExistingProjectDocumentSettingsModel {
      return ExistingProjectDocumentSettingsModel(
          dropboxFolderPath = record[PROJECT_DOCUMENT_SETTINGS.DROPBOX_FOLDER_PATH]!!,
          fileNaming = record[PROJECT_DOCUMENT_SETTINGS.FILE_NAMING]!!,
          googleFolderUrl = record[PROJECT_DOCUMENT_SETTINGS.GOOGLE_FOLDER_URL]!!,
          projectId = record[PROJECT_DOCUMENT_SETTINGS.PROJECT_ID]!!,
      )
    }
  }
}

typealias ExistingProjectDocumentSettingsModel = ProjectDocumentSettingsModel<ProjectId>
