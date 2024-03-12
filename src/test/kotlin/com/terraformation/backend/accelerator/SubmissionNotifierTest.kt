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
import com.terraformation.backend.mockUser
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SubmissionNotifierTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val eventPublisher = TestEventPublisher()

  private val notifier: SubmissionNotifier by lazy {
    SubmissionNotifier(
        TestClock(),
        DeliverableStore(dslContext),
        eventPublisher,
        mockk(),
        SystemUser(usersDao),
    )
  }

  private lateinit var deliverableId: DeliverableId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertModule()
    insertCohort()
    insertCohortModule()
    insertParticipant(cohortId = inserted.cohortId)

    projectId = insertProject(participantId = inserted.participantId)
    deliverableId = insertDeliverable()
  }

  @Nested
  inner class NotifyIfNoNewerUploads {
    @Test
    fun `does not publish event if there are newer documents`() {
      insertSubmission()

      val documentId = insertSubmissionDocument()
      insertSubmissionDocument()

      notifier.notifyIfNoNewerUploads(
          DeliverableDocumentUploadedEvent(deliverableId, documentId, projectId))

      eventPublisher.assertEventNotPublished(DeliverableReadyForReviewEvent::class.java)
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
}
