package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_WORKFLOW_HISTORY
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableReviewedEvent
import com.terraformation.backend.documentproducer.model.ExistingVariableWorkflowHistoryModel
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class VariableWorkflowStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  override val tablesToResetSequences = listOf(VARIABLE_WORKFLOW_HISTORY)

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store by lazy {
    VariableWorkflowStore(
        clock,
        dslContext,
        eventPublisher,
        variablesDao,
    )
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertDocumentTemplate()
    insertVariableManifest()
    insertDocument()
    insertModule()
    insertDeliverable()
    insertVariableManifestEntry(
        insertTextVariable(
            id =
                insertVariable(
                    deliverableId = inserted.deliverableId,
                    deliverablePosition = 0,
                    type = VariableType.Text)))
    insertValue(variableId = inserted.variableId)

    every { user.canReadProject(any()) } returns true
    every { user.canUpdateInternalVariableWorkflowDetails(any()) } returns true
  }

  @Nested
  inner class PublishEvent {
    @Test
    fun `publishes event for the first workflow history`() {
      val newWorkflowHistory =
          store.update(
              projectId = inserted.projectId,
              variableId = inserted.variableId,
              status = VariableWorkflowStatus.Approved,
              feedback = null,
              internalComment = null,
          )

      eventPublisher.assertEventPublished(
          QuestionsDeliverableReviewedEvent(
              inserted.deliverableId,
              inserted.projectId,
              mapOf(inserted.variableId to newWorkflowHistory),
          ))
    }

    @Test
    fun `publishes event for deliverable with all latest workflow`() {
      val variableId = inserted.variableId
      insertVariableWorkflowHistory(
          status = VariableWorkflowStatus.Rejected,
          feedback = null,
          internalComment = null,
      )

      val otherVariableId =
          insertVariableManifestEntry(
              insertTextVariable(
                  id =
                      insertVariable(
                          deliverableId = inserted.deliverableId,
                          deliverablePosition = 0,
                          type = VariableType.Text)))
      insertValue(variableId = otherVariableId)
      val otherWorkflowId =
          insertVariableWorkflowHistory(
              status = VariableWorkflowStatus.Rejected,
              feedback = null,
              internalComment = null,
          )

      val otherWorkflowHistory =
          ExistingVariableWorkflowHistoryModel(
              variableWorkflowHistoryDao.fetchOneById(otherWorkflowId)!!)

      val updatedWorkflowHistory =
          store.update(
              projectId = inserted.projectId,
              variableId = variableId,
              status = VariableWorkflowStatus.Approved,
              feedback = null,
              internalComment = null,
          )

      eventPublisher.assertEventPublished(
          QuestionsDeliverableReviewedEvent(
              inserted.deliverableId,
              inserted.projectId,
              mapOf(
                  variableId to updatedWorkflowHistory,
                  otherVariableId to otherWorkflowHistory,
              ),
          ))
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
