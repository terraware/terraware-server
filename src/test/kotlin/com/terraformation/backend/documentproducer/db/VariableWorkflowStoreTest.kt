package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableReviewedEvent
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class VariableWorkflowStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store by lazy { VariableWorkflowStore(clock, dslContext, eventPublisher) }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertDocumentTemplate()
    insertVariableManifest()
    insertDocument()
    insertModule()
    insertDeliverable()
    insertVariableManifestEntry(insertTextVariable(deliverableId = inserted.deliverableId))

    insertValue(variableId = inserted.variableId)

    every { user.canReadProject(any()) } returns true
    every { user.canUpdateInternalVariableWorkflowDetails(any()) } returns true
  }

  @Nested
  inner class PublishEvent {
    @Test
    fun `publishes event for the first workflow action`() {
      store.update(
          projectId = inserted.projectId,
          variableId = inserted.variableId,
          status = VariableWorkflowStatus.Approved,
          feedback = null,
          internalComment = null,
      )

      eventPublisher.assertEventPublished(
          QuestionsDeliverableReviewedEvent(inserted.deliverableId, inserted.projectId))
    }

    @Test
    fun `publishes event for deliverable`() {
      val variableId = inserted.variableId
      insertVariableWorkflowHistory(
          status = VariableWorkflowStatus.Rejected,
          feedback = null,
          internalComment = null,
      )

      val otherVariableId =
          insertVariableManifestEntry(insertTextVariable(deliverableId = inserted.deliverableId))

      insertValue(variableId = otherVariableId)

      store.update(
          projectId = inserted.projectId,
          variableId = variableId,
          status = VariableWorkflowStatus.Approved,
          feedback = null,
          internalComment = null,
      )

      eventPublisher.assertEventPublished(
          QuestionsDeliverableReviewedEvent(inserted.deliverableId, inserted.projectId))
    }

    @Test
    fun `does not publish event for internal comment changes`() {
      val variableId = inserted.variableId
      insertVariableWorkflowHistory(
          status = VariableWorkflowStatus.Rejected,
          feedback = "Unchanged feedback",
          internalComment = "Old internal comment",
      )

      store.update(
          projectId = inserted.projectId,
          variableId = variableId,
          status = VariableWorkflowStatus.Rejected,
          feedback = "Unchanged feedback",
          internalComment = "New internal comment",
      )

      eventPublisher.assertEventNotPublished<QuestionsDeliverableReviewedEvent>()
    }

    @Test
    fun `does not publish event if a deliverable is not associated`() {
      val nonDeliverableVariable =
          insertVariableManifestEntry(
              insertTextVariable(
                  id = insertVariable(deliverableId = null, type = VariableType.Text)))

      insertValue(variableId = nonDeliverableVariable)

      store.update(
          projectId = inserted.projectId,
          variableId = nonDeliverableVariable,
          status = VariableWorkflowStatus.Approved,
          feedback = null,
          internalComment = null,
      )

      eventPublisher.assertEventNotPublished<QuestionsDeliverableReviewedEvent>()
    }
  }
}
