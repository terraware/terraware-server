package com.terraformation.backend.funder

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.funder.db.PublishedProjectDetailsStore
import com.terraformation.backend.funder.model.FunderProjectDetailsModel
import com.terraformation.backend.funder.model.PublishedProjectNameModel
import jakarta.inject.Named

@Named
class FunderProjectService(
    private val publishedProjectDetailsStore: PublishedProjectDetailsStore,
) {

  fun fetchAll(): List<PublishedProjectNameModel> {
    requirePermissions { readPublishedProjects() }

    return publishedProjectDetailsStore.fetchAll()
  }

  fun fetchByProjectId(projectId: ProjectId): FunderProjectDetailsModel? {
    requirePermissions { readProjectFunderDetails(projectId) }

    return publishedProjectDetailsStore.fetchOneById(projectId)
  }

  fun fetchListByProjectIds(projectIds: Set<ProjectId>): List<FunderProjectDetailsModel> {
    return projectIds.mapNotNull { fetchByProjectId(it) }
  }

  fun publishProjectProfile(model: FunderProjectDetailsModel) {
    requirePermissions { publishProjectProfileDetails() }
    publishedProjectDetailsStore.publish(model)
  }
}
