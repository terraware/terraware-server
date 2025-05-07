package com.terraformation.backend.funder

import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.funder.model.FunderProjectDetailsModel
import jakarta.inject.Named

@Named
class FunderProjectService(
    private val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService,
    private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore,
) {
  fun fetchByProjectId(projectId: ProjectId): FunderProjectDetailsModel {
    // TODO should this instead be `fetchFunderValues`? Will need some way to get past the
    // readProjectAcceleratorDetails(projectId) check
    val variableValues = acceleratorProjectVariableValuesService.fetchValues(projectId)
    return projectAcceleratorDetailsStore.fetchOneByIdForFunder(projectId, variableValues)
  }
}
