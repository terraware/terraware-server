package com.terraformation.backend.funder

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.funder.db.PublishedProjectDetailsStore
import com.terraformation.backend.funder.model.FunderProjectDetailsModel
import jakarta.inject.Named

@Named
class FunderProjectService(
    private val publishedProjectDetailsStore: PublishedProjectDetailsStore,
) {
  fun fetchByProjectId(projectId: ProjectId): FunderProjectDetailsModel {
    requirePermissions { readProjectFunderDetails(projectId) }

    return publishedProjectDetailsStore.fetchOneById(projectId)
        ?: throw ProjectNotFoundException(projectId)
  }

  fun publishProjectProfile(model: FunderProjectDetailsModel) {
    requirePermissions { publishProjectProfileDetails() }
    publishedProjectDetailsStore.publish(model)
  }
}
