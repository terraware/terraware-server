package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.accelerator.ApplicationModuleStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableSubmittedEvent
import jakarta.inject.Named
import org.springframework.context.event.EventListener

/** Marks deliverables as complete when the user has supplied their information. */
@Named
class DeliverableCompleter(
    private val applicationStore: ApplicationStore,
    private val deliverableStore: DeliverableStore,
    private val moduleStore: ModuleStore,
    private val submissionStore: SubmissionStore,
    private val systemUser: SystemUser,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
) {
  @EventListener
  fun on(event: DeliverableDocumentUploadedEvent) {
    completeApplicationDeliverable(event.deliverableId, event.projectId)
  }

  @EventListener
  fun on(event: ParticipantProjectSpeciesAddedEvent) {
    completeApplicationDeliverable(event.deliverableId, event.participantProjectSpecies.projectId)
  }

  @EventListener
  fun on(event: QuestionsDeliverableSubmittedEvent) {
    completeApplicationDeliverable(event.deliverableId, event.projectId) {
      val variables = variableStore.fetchDeliverableVariables(event.deliverableId)
      val valuesByVariableId =
          variableValueStore.listValues(event.projectId, event.deliverableId).associateBy {
            it.variableId
          }

      // Mark deliverable as complete if all variables that don't depend on other variables have
      // values.
      variables
          .filter { it.dependencyVariableStableId == null }
          .none { it.id !in valuesByVariableId }
    }
  }

  /**
   * Marks a deliverable as completed if it is in a module in one of the application phases. If all
   * the deliverables in the module are completed, marks the module as completed too.
   *
   * @param predicate Only mark the deliverable as completed if this returns true. The predicate is
   *   only called if the deliverable's module is in an application phase.
   */
  private fun completeApplicationDeliverable(
      deliverableId: DeliverableId,
      projectId: ProjectId,
      predicate: (() -> Boolean)? = null
  ) {
    systemUser.run {
      val moduleId = deliverableStore.fetchDeliverableModuleId(deliverableId)
      val phase = moduleStore.fetchCohortPhase(moduleId)

      if (phase == CohortPhase.PreScreen || phase == CohortPhase.Application) {
        if (predicate == null || predicate()) {
          submissionStore.createSubmission(deliverableId, projectId, SubmissionStatus.Completed)

          if (submissionStore.moduleDeliverablesAllCompleted(deliverableId, projectId)) {
            applicationStore.updateModuleStatus(
                projectId, moduleId, ApplicationModuleStatus.Complete)
          }
        }
      }
    }
  }
}
