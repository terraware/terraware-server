package com.terraformation.backend.customer.model

import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectsRow

data class ProjectModel<ID : ProjectId?>(
    val id: ID,
    val name: String,
    val organizationId: OrganizationId,
    val description: String? = null,
    val participantId: ParticipantId? = null,
) {
  companion object {
    fun of(row: ProjectsRow): ExistingProjectModel {
      return ExistingProjectModel(
          row.id!!, row.name!!, row.organizationId!!, row.description, row.participantId)
    }
  }
}

typealias NewProjectModel = ProjectModel<Nothing?>

typealias ExistingProjectModel = ProjectModel<ProjectId>
