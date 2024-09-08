package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.accelerator.ApplicationModuleStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.ProjectId
import javax.inject.Named

@Named
class DeliverableService(
    private val applicationStore: ApplicationStore,
    private val deliverableStore: DeliverableStore,
    private val moduleStore: ModuleStore,
    private val submissionStore: SubmissionStore,
    private val systemUser: SystemUser,
) {
  fun completeDeliverable(deliverableId: DeliverableId, projectId: ProjectId): SubmissionId {
    val deliverableModule =
        moduleStore.fetchOneById(deliverableStore.fetchDeliverableModuleId(deliverableId))

    val isApplicationModule =
        deliverableModule.phase == CohortPhase.PreScreen ||
            deliverableModule.phase == CohortPhase.Application

    val submissionId =
        submissionStore.createSubmission(deliverableId, projectId, SubmissionStatus.Completed)

    if (isApplicationModule &&
        submissionStore.moduleDeliverablesAllCompleted(deliverableId, projectId)) {

      systemUser.run {
        applicationStore.updateModuleStatus(
            projectId, deliverableModule.id, ApplicationModuleStatus.Complete)
      }
    }

    return submissionId
  }
}
