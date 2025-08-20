package com.terraformation.backend.accelerator

import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadedEvent
import com.terraformation.backend.accelerator.event.DeliverableReadyForReviewEvent
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableReviewedEvent
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableStatusUpdatedEvent
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableSubmittedEvent
import org.junit.jupiter.api.Test

class SubmissionNotifierTest {
  private val rateLimitedEventPublisher = TestEventPublisher()

  private val notifier = SubmissionNotifier(rateLimitedEventPublisher)

  @Test
  fun `publishes DeliverableReadyForReviewEvent on document upload`() {
    val event =
        DeliverableDocumentUploadedEvent(DeliverableId(1), SubmissionDocumentId(2), ProjectId(3))

    notifier.on(event)

    rateLimitedEventPublisher.assertEventPublished(
        DeliverableReadyForReviewEvent(event.deliverableId, event.projectId)
    )
    assertIsEventListener<DeliverableDocumentUploadedEvent>(notifier)
  }

  @Test
  fun `publishes DeliverableReadyForReviewEvent on questionnaire deliverable submission`() {
    val event = QuestionsDeliverableSubmittedEvent(DeliverableId(1), ProjectId(2))

    notifier.on(event)

    rateLimitedEventPublisher.assertEventPublished(
        DeliverableReadyForReviewEvent(event.deliverableId, event.projectId)
    )
    assertIsEventListener<QuestionsDeliverableSubmittedEvent>(notifier)
  }

  @Test
  fun `publishes QuestionsDeliverableStatusUpdatedEvent on questions deliverable review`() {
    val event = QuestionsDeliverableReviewedEvent(DeliverableId(1), ProjectId(2))

    notifier.on(event)

    rateLimitedEventPublisher.assertEventPublished(
        QuestionsDeliverableStatusUpdatedEvent(event.deliverableId, event.projectId)
    )
    assertIsEventListener<QuestionsDeliverableReviewedEvent>(notifier)
  }
}
