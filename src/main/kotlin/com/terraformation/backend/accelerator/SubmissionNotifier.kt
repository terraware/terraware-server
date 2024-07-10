package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadedEvent
import com.terraformation.backend.accelerator.event.DeliverableReadyForReviewEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.db.VariableWorkflowStore
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableReviewedEvent
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableStatusUpdatedEvent
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableSubmittedEvent
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.Duration
import java.time.InstantSource
import org.jobrunr.scheduling.JobScheduler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener

@Named
class SubmissionNotifier(
    private val clock: InstantSource,
    private val deliverableStore: DeliverableStore,
    private val eventPublisher: ApplicationEventPublisher,
    @Lazy private val scheduler: JobScheduler,
    private val systemUser: SystemUser,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
    private val variableWorkflowStore: VariableWorkflowStore,
) {
  companion object {
    /**
     * Wait this long before notifying about new submissions to give the user time to submit
     * additional items for a deliverable. This is the delay after the _last_ submission, that is,
     * the user-visible behavior is that the countdown resets each time something new is submitted
     * for a deliverable.
     */
    private val notificationDelay = Duration.ofMinutes(5)

    private val log = perClassLogger()
  }

  /** Schedules a "ready for review" notification when a document is uploaded. */
  @EventListener
  fun on(event: DeliverableDocumentUploadedEvent) {
    scheduler.schedule<SubmissionNotifier>(clock.instant().plus(notificationDelay)) {
      notifyIfNoNewerUploads(event)
    }
  }

  /** Schedules a "ready for review" notification when a question is answered. */
  @EventListener
  fun on(event: QuestionsDeliverableSubmittedEvent) {
    scheduler.schedule<SubmissionNotifier>(clock.instant().plus(notificationDelay)) {
      notifyIfNoNewerSubmissions(event)
    }
  }

  /** Schedules a "ready for review" notification when a question is answered. */
  @EventListener
  fun on(event: QuestionsDeliverableReviewedEvent) {
    scheduler.schedule<SubmissionNotifier>(clock.instant().plus(notificationDelay)) {
      notifyIfNoNewerReviews(event)
    }
  }

  /**
   * Publishes [DeliverableReadyForReviewEvent] if no documents have been uploaded for a submission
   * since the one referenced by the event.
   */
  fun notifyIfNoNewerUploads(event: DeliverableDocumentUploadedEvent) {
    systemUser.run {
      val deliverable =
          deliverableStore
              .fetchDeliverableSubmissions(
                  projectId = event.projectId, deliverableId = event.deliverableId)
              .firstOrNull()

      if (deliverable != null) {
        val maxDocumentId = deliverable.documents.map { it.id }.maxBy { it.value }

        if (maxDocumentId == event.documentId) {
          eventPublisher.publishEvent(
              DeliverableReadyForReviewEvent(event.deliverableId, event.projectId))
        }
      } else {
        log.error("Deliverable ${event.deliverableId} not found for project ${event.projectId}")
      }
    }
  }

  /**
   * Publishes [DeliverableReadyForReviewEvent] if no question answers have been submitted since the
   * one referenced by the event.
   */
  fun notifyIfNoNewerSubmissions(event: QuestionsDeliverableSubmittedEvent) {
    systemUser.run {
      val allValuesLatest =
          event.currentValueIds.all {
            variableValueStore.fetchMaxValueId(event.projectId, it.key) == it.value
          }

      if (allValuesLatest) {
        eventPublisher.publishEvent(
            DeliverableReadyForReviewEvent(event.deliverableId, event.projectId))
      }
    }
  }

  /**
   * Publishes [QuestionsDeliverableStatusUpdatedEvent] if no user-visible feedback or status has
   * been updated since the one referenced by the event.
   */
  fun notifyIfNoNewerReviews(event: QuestionsDeliverableReviewedEvent) {
    systemUser.run {
      val deliverableVariableWorkflowsForProject =
          variableWorkflowStore.fetchCurrentForProjectDeliverable(
              event.projectId, event.deliverableId)

      if (event.currentWorkflows.keys == deliverableVariableWorkflowsForProject.keys &&
          deliverableVariableWorkflowsForProject.all { (variableId, historyModel) ->
            val deliverableWorkflow = event.currentWorkflows[variableId]
            deliverableWorkflow != null &&
                deliverableWorkflow.status == historyModel.status &&
                deliverableWorkflow.feedback == historyModel.feedback
          }) {
        eventPublisher.publishEvent(
            QuestionsDeliverableStatusUpdatedEvent(event.deliverableId, event.projectId))
      }
    }
  }
}
