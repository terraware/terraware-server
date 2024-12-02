package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
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

  /** Returns accelerator details for projects that are assigned to cohorts. */
  fun fetchParticipantProjectDetails(): List<ProjectAcceleratorDetailsModel> {
    requirePermissions { readAllAcceleratorDetails() }

    return projectAcceleratorDetailsStore.fetch(COHORTS.ID.isNotNull()) {
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
        projectId, applyFunc(variableValues.toProjectAcceleratorDetails()).toVariableValuesModel())
  }
}
