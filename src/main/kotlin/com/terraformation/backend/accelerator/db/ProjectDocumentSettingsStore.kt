package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingProjectDocumentSettingsModel
import com.terraformation.backend.accelerator.model.ProjectDocumentSettingsModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_DOCUMENT_SETTINGS
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class ProjectDocumentSettingsStore(
    private val dslContext: DSLContext,
) {
  fun fetchOneById(projectId: ProjectId): ExistingProjectDocumentSettingsModel {
    return fetch(PROJECT_DOCUMENT_SETTINGS.PROJECT_ID.eq(projectId)).firstOrNull()
        ?: throw ProjectDocumentSettingsNotConfiguredException(projectId)
  }

  private fun fetch(condition: Condition?): List<ExistingProjectDocumentSettingsModel> {
    return with(PROJECT_DOCUMENT_SETTINGS) {
      dslContext
          .select(PROJECT_DOCUMENT_SETTINGS.asterisk())
          .from(PROJECT_DOCUMENT_SETTINGS)
          .apply { condition?.let { where(it) } }
          .orderBy(PROJECT_ID)
          .fetch { ProjectDocumentSettingsModel.of(it) }
          .filter { currentUser().canReadProjectDocumentSettings(it.projectId) }
    }
  }
}
