package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.DeliverableNotFoundException
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.accelerator.ApplicationModuleStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableSubmittedEvent
import jakarta.inject.Named
import org.springframework.context.ApplicationEventPublisher

@Named
class DeliverableService(
    private val applicationStore: ApplicationStore,
    private val deliverableStore: DeliverableStore,
    private val eventPublisher: ApplicationEventPublisher,
    private val moduleStore: ModuleStore,
    private val submissionStore: SubmissionStore,
    private val systemUser: SystemUser,
) {
  fun submitDeliverable(
      deliverableId: DeliverableId,
      projectId: ProjectId,
  ): SubmissionId {
    val existing =
        systemUser.run {
          deliverableStore.fetchDeliverables(deliverableId = deliverableId).firstOrNull()
              ?: throw DeliverableNotFoundException(deliverableId)
        }

    val result =
        submissionStore.createSubmission(deliverableId, projectId, SubmissionStatus.InReview)

    if (existing.type == DeliverableType.Questions) {
      eventPublisher.publishEvent(QuestionsDeliverableSubmittedEvent(deliverableId, projectId))
    }

    return result
  }

  fun setDeliverableCompletion(
      deliverableId: DeliverableId,
      projectId: ProjectId,
      isComplete: Boolean,
  ): SubmissionId {
    val deliverableModule =
        moduleStore.fetchOneById(deliverableStore.fetchDeliverableModuleId(deliverableId))

    val isApplicationModule =
        deliverableModule.phase == CohortPhase.PreScreen ||
            deliverableModule.phase == CohortPhase.Application

    val status =
        if (isComplete) {
          SubmissionStatus.Completed
        } else {
          SubmissionStatus.NotSubmitted
        }

    val submissionId = submissionStore.createSubmission(deliverableId, projectId, status)

    if (isApplicationModule) {
      systemUser.run {
        if (submissionStore.moduleDeliverablesAllCompleted(deliverableId, projectId)) {
          applicationStore.updateModuleStatus(
              projectId,
              deliverableModule.id,
              ApplicationModuleStatus.Complete,
          )
        } else {
          applicationStore.updateModuleStatus(
              projectId,
              deliverableModule.id,
              ApplicationModuleStatus.Incomplete,
          )
        }
      }
    }

    return submissionId
  }
}
