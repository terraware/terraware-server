package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.springframework.beans.factory.annotation.Value

@Named
class PreScreenBoundarySubmissionFetcher(
    private val applicationStore: ApplicationStore,
    @Value("68") // From deliverables spreadsheet
    val boundaryDeliverableId: DeliverableId,
) {
  companion object {
    private val log = perClassLogger()
  }

  fun fetchSubmission(projectId: ProjectId): DeliverableSubmissionModel? {
    requirePermissions { readProjectDeliverables(projectId) }

    val submission =
        applicationStore
            .fetchApplicationDeliverables(
                projectId = projectId,
                deliverableId = boundaryDeliverableId,
            )
            .firstOrNull()

    if (submission == null) {
      log.error(
          "Submission not found for deliverable $boundaryDeliverableId and project $projectId"
      )
    }

    return submission
  }
}
