package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadedEvent
import com.terraformation.backend.accelerator.event.DeliverableReadyForReviewEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.db.VariableWorkflowStore
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableReviewedEvent
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableStatusUpdatedEvent
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableSubmittedEvent
import com.terraformation.backend.documentproducer.model.ExistingVariableWorkflowHistoryModel
import com.terraformation.backend.mockUser
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SubmissionNotifierTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val notifier: SubmissionNotifier by lazy {
    SubmissionNotifier(
        clock,
        DeliverableStore(dslContext),
        eventPublisher,
        mockk(),
        SystemUser(usersDao),
        VariableStore(
            dslContext,
            variableNumbersDao,
            variablesDao,
            variableSectionRecommendationsDao,
            variableSectionsDao,
            variableSelectsDao,
            variableSelectOptionsDao,
            variableTablesDao,
            variableTableColumnsDao,
            variableTextsDao),
        VariableValueStore(
            clock,
            documentsDao,
            dslContext,
            eventPublisher,
            variableImageValuesDao,
            variableLinkValuesDao,
            variablesDao,
            variableSectionValuesDao,
            variableSelectOptionValuesDao,
            variableValuesDao,
            variableValueTableRowsDao),
        VariableWorkflowStore(
            clock,
            dslContext,
            eventPublisher,
            variablesDao,
        ))
  }

  private lateinit var deliverableId: DeliverableId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertModule()
    insertCohort()
    insertCohortModule()
    insertParticipant(cohortId = inserted.cohortId)

    projectId = insertProject(participantId = inserted.participantId)
    deliverableId = insertDeliverable()
  }

  @Nested
  inner class NotifyIfNoNewerDocumentSubmission {
    @Test
    fun `does not publish event if there are newer documents`() {
      insertSubmission()

      val documentId = insertSubmissionDocument()
      insertSubmissionDocument()

      notifier.notifyIfNoNewerUploads(
          DeliverableDocumentUploadedEvent(deliverableId, documentId, projectId))

      eventPublisher.assertEventNotPublished<DeliverableReadyForReviewEvent>()
    }

    @Test
    fun `publishes event if this is the latest document`() {
      insertSubmission()

      insertSubmissionDocument()
      val documentId = insertSubmissionDocument()

      notifier.notifyIfNoNewerUploads(
          DeliverableDocumentUploadedEvent(deliverableId, documentId, projectId))

      eventPublisher.assertEventPublished(DeliverableReadyForReviewEvent(deliverableId, projectId))
    }
  }

  @Nested
  inner class NotifyIfNoNewerQuestionSubmission {
    @BeforeEach
    fun setup() {
      insertDocumentTemplate()
      insertVariableManifest()
      insertDocument()

      insertVariableManifestEntry(
          insertTextVariable(
              id =
                  insertVariable(
                      deliverableId = inserted.deliverableId,
                      deliverablePosition = 0,
                      type = VariableType.Text)))
    }

    @Test
    fun `does not publish event if there are newer variable values`() {
      val oldValueId = insertValue(variableId = inserted.variableId, textValue = "Old")
      insertValue(variableId = inserted.variableId, textValue = "New")

      notifier.notifyIfNoNewerSubmissions(
          QuestionsDeliverableSubmittedEvent(
              deliverableId, projectId, mapOf(inserted.variableId to oldValueId)))

      eventPublisher.assertEventNotPublished<DeliverableReadyForReviewEvent>()
    }

    @Test
    fun `publishes event if these are the latest variable values`() {
      val valueId = insertValue(variableId = inserted.variableId, textValue = "Only")

      notifier.notifyIfNoNewerSubmissions(
          QuestionsDeliverableSubmittedEvent(
              deliverableId, projectId, mapOf(inserted.variableId to valueId)))

      eventPublisher.assertEventPublished(DeliverableReadyForReviewEvent(deliverableId, projectId))
    }
  }

  @Nested
  inner class NotifyIfNoNewerReviews {
    @BeforeEach
    fun setup() {
      insertDocumentTemplate()
      insertVariableManifest()
      insertDocument()

      insertVariableManifestEntry(
          insertTextVariable(
              id =
                  insertVariable(
                      deliverableId = inserted.deliverableId,
                      deliverablePosition = 0,
                      type = VariableType.Text)))

      insertValue(variableId = inserted.variableId)
    }

    @Test
    fun `does not publish event if there are newer variable workflows`() {
      val oldWorkflowHistoryId =
          insertVariableWorkflowHistory(
              feedback = "old feedback",
              status = VariableWorkflowStatus.InReview,
          )

      insertVariableWorkflowHistory(
          feedback = "new feedback",
          status = VariableWorkflowStatus.Approved,
      )

      val oldWorkflowHistory =
          ExistingVariableWorkflowHistoryModel(
              variableWorkflowHistoryDao.fetchOneById(oldWorkflowHistoryId)!!)

      notifier.notifyIfNoNewerReviews(
          QuestionsDeliverableReviewedEvent(
              inserted.deliverableId,
              inserted.projectId,
              mapOf(inserted.variableId to oldWorkflowHistory)))

      eventPublisher.assertEventNotPublished<QuestionsDeliverableStatusUpdatedEvent>()
    }

    @Test
    fun `publishes event if these are the latest variable workflows`() {
      val workflowId =
          insertVariableWorkflowHistory(
              feedback = "feedback",
              status = VariableWorkflowStatus.Approved,
          )

      val workflowHistory =
          ExistingVariableWorkflowHistoryModel(
              variableWorkflowHistoryDao.fetchOneById(workflowId)!!)

      notifier.notifyIfNoNewerReviews(
          QuestionsDeliverableReviewedEvent(
              inserted.deliverableId,
              inserted.projectId,
              mapOf(inserted.variableId to workflowHistory)))

      eventPublisher.assertEventPublished(
          QuestionsDeliverableStatusUpdatedEvent(deliverableId, projectId))
    }

    @Test
    fun `publishes event if newer variable workflows contain only internal comment changes`() {
      val oldWorkflowId =
          insertVariableWorkflowHistory(
              feedback = "unchanged feedback",
              status = VariableWorkflowStatus.Approved,
          )

      insertVariableWorkflowHistory(
          feedback = "unchanged feedback",
          status = VariableWorkflowStatus.Approved,
          internalComment = "added internal comment",
      )

      val oldWorkflowHistory =
          ExistingVariableWorkflowHistoryModel(
              variableWorkflowHistoryDao.fetchOneById(oldWorkflowId)!!)

      notifier.notifyIfNoNewerReviews(
          QuestionsDeliverableReviewedEvent(
              inserted.deliverableId,
              inserted.projectId,
              mapOf(inserted.variableId to oldWorkflowHistory)))

      eventPublisher.assertEventPublished(
          QuestionsDeliverableStatusUpdatedEvent(deliverableId, projectId))
    }
  }
}
