package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named

@Named
class ProjectAcceleratorDetailsService(
    private val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService,
    private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore,
) {
  fun fetchOneById(projectId: ProjectId): ProjectAcceleratorDetailsModel {
    val variableValues = acceleratorProjectVariableValuesService.fetchValues(projectId)
    return projectAcceleratorDetailsStore.fetchOneById(projectId, variableValues)
  }

  /** Returns accelerator details for projects that are in phases. */
  fun fetchAllParticipantProjectDetails(): List<ProjectAcceleratorDetailsModel> {
    return projectAcceleratorDetailsStore.fetch(PROJECTS.PHASE_ID.isNotNull()) {
      acceleratorProjectVariableValuesService.fetchValues(it)
    }
  }

  fun update(
      projectId: ProjectId,
      applyFunc: (ProjectAcceleratorDetailsModel) -> ProjectAcceleratorDetailsModel,
  ) {
    val variableValues = acceleratorProjectVariableValuesService.fetchValues(projectId)
    projectAcceleratorDetailsStore.update(projectId, variableValues, applyFunc)

    acceleratorProjectVariableValuesService.writeValues(
        projectId,
        applyFunc(variableValues.toProjectAcceleratorDetails()).toVariableValuesModel(),
    )
  }
}
