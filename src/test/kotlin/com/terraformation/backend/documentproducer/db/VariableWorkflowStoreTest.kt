package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableReviewedEvent
import com.terraformation.backend.documentproducer.model.ExistingVariableWorkflowHistoryModel
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

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
    every { user.canReadInternalVariableWorkflowDetails(any()) } returns true
    every { user.canUpdateInternalVariableWorkflowDetails(any()) } returns true
  }

  @Nested
  inner class FetchProjectVariableHistory {
    @Test
    fun `returns list of workflow details sorted by createdTime`() {
      val oldWorkflowId =
          insertVariableWorkflowHistory(
              createdTime = clock.instant.plusSeconds(600),
              feedback = "old feedback",
              internalComment = "old comment",
              status = VariableWorkflowStatus.InReview,
          )

      val oldestWorkflowId =
          insertVariableWorkflowHistory(
              createdTime = clock.instant.plusSeconds(300),
              status = VariableWorkflowStatus.NotSubmitted,
          )

      val newWorkflowId =
          insertVariableWorkflowHistory(
              createdTime = clock.instant.plusSeconds(900),
              feedback = "new feedback",
              internalComment = "new comment",
              status = VariableWorkflowStatus.Approved,
          )

      assertEquals(
          listOf(
              ExistingVariableWorkflowHistoryModel(
                  createdBy = user.userId,
                  createdTime = clock.instant.plusSeconds(900),
                  feedback = "new feedback",
                  id = newWorkflowId,
                  internalComment = "new comment",
                  maxVariableValueId = inserted.variableValueId,
                  projectId = inserted.projectId,
                  status = VariableWorkflowStatus.Approved,
                  variableId = inserted.variableId,
              ),
              ExistingVariableWorkflowHistoryModel(
                  createdBy = user.userId,
                  createdTime = clock.instant.plusSeconds(600),
                  feedback = "old feedback",
                  id = oldWorkflowId,
                  internalComment = "old comment",
                  maxVariableValueId = inserted.variableValueId,
                  projectId = inserted.projectId,
                  status = VariableWorkflowStatus.InReview,
                  variableId = inserted.variableId,
              ),
              ExistingVariableWorkflowHistoryModel(
                  createdBy = user.userId,
                  createdTime = clock.instant.plusSeconds(300),
                  feedback = null,
                  id = oldestWorkflowId,
                  internalComment = null,
                  maxVariableValueId = inserted.variableValueId,
                  projectId = inserted.projectId,
                  status = VariableWorkflowStatus.NotSubmitted,
                  variableId = inserted.variableId,
              ),
          ),
          store.fetchProjectVariableHistory(inserted.projectId, inserted.variableId))
    }

    @Test
    fun `throws exception if no permission to read internal workflow details`() {
      every { user.canReadInternalVariableWorkflowDetails(any()) } returns false
      assertThrows<AccessDeniedException> {
        store.fetchProjectVariableHistory(inserted.projectId, inserted.variableId)
      }
    }
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
