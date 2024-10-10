package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.db.default_schema.ProjectId
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

  fun update(
      projectId: ProjectId,
      applyFunc: (ProjectAcceleratorDetailsModel) -> ProjectAcceleratorDetailsModel,
  ) {
    projectAcceleratorDetailsStore.update(projectId, applyFunc)

    val variableValues = acceleratorProjectVariableValuesService.fetchValues(projectId)
    acceleratorProjectVariableValuesService.writeValues(
        projectId, applyFunc(variableValues.toProjectAcceleratorDetails()).toVariableValuesModel())
  }
}
