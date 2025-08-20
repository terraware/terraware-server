package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadedEvent
import com.terraformation.backend.accelerator.event.DeliverableReadyForReviewEvent
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableReviewedEvent
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableStatusUpdatedEvent
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableSubmittedEvent
import com.terraformation.backend.ratelimit.RateLimitedEventPublisher
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class SubmissionNotifier(
    private val rateLimitedEventPublisher: RateLimitedEventPublisher,
) {
  /** Schedules a "ready for review" notification when a document is uploaded. */
  @EventListener
  fun on(event: DeliverableDocumentUploadedEvent) {
    rateLimitedEventPublisher.publishEvent(
        DeliverableReadyForReviewEvent(event.deliverableId, event.projectId)
    )
  }

  /** Schedules a "ready for review" notification when a question is answered. */
  @EventListener
  fun on(event: QuestionsDeliverableSubmittedEvent) {
    rateLimitedEventPublisher.publishEvent(
        DeliverableReadyForReviewEvent(event.deliverableId, event.projectId)
    )
  }

  /** Schedules a "ready for review" notification when a question is answered. */
  @EventListener
  fun on(event: QuestionsDeliverableReviewedEvent) {
    rateLimitedEventPublisher.publishEvent(
        QuestionsDeliverableStatusUpdatedEvent(event.deliverableId, event.projectId)
    )
  }
}
