package com.terraformation.backend.funder

import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.funder.db.PublishedProjectDetailsStore
import com.terraformation.backend.funder.model.FunderProjectDetailsModel
import jakarta.inject.Named

@Named
class FunderProjectService(
    private val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService,
    private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore,
    private val systemUser: SystemUser,
    private val publishedProjectDetailsStore: PublishedProjectDetailsStore,
) {
  fun fetchByProjectId(projectId: ProjectId): FunderProjectDetailsModel {
    requirePermissions { readProjectFunderDetails(projectId) }
    return systemUser.run {
      val variableValues = acceleratorProjectVariableValuesService.fetchValues(projectId)
      projectAcceleratorDetailsStore.fetchOneByIdForFunder(projectId, variableValues)
    }
  }

  fun publishProjectProfile(model: FunderProjectDetailsModel) {
    requirePermissions { publishProjectProfileDetails() }
    publishedProjectDetailsStore.publish(model)
  }
}
