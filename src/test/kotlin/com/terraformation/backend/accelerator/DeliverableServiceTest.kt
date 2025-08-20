package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ApplicationModuleStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.records.ApplicationModulesRecord
import com.terraformation.backend.db.accelerator.tables.records.SubmissionsRecord
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableSubmittedEvent
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DeliverableServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val messages = Messages()

  private val service: DeliverableService by lazy {
    DeliverableService(
        ApplicationStore(
            clock,
            countriesDao,
            CountryDetector(),
            dslContext,
            eventPublisher,
            messages,
        ),
        DeliverableStore(dslContext),
        eventPublisher,
        ModuleStore(dslContext),
        SubmissionStore(clock, dslContext, eventPublisher),
        SystemUser(usersDao),
    )
  }

  private lateinit var deliverable1: DeliverableId
  private lateinit var deliverable2: DeliverableId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertApplication()
    insertModule(phase = CohortPhase.PreScreen)
    insertApplicationModule(
        inserted.applicationId,
        inserted.moduleId,
        ApplicationModuleStatus.Incomplete,
    )

    deliverable1 = insertDeliverable(isRequired = true)
    deliverable2 = insertDeliverable(isRequired = true)

    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectDeliverables(any()) } returns true
    every { user.canReadModule(any()) } returns true
    every { user.canCreateSubmission(any()) } returns true
  }

  @Nested
  inner class SetDeliverableCompletion {
    @Test
    fun `creates a submission with Complete status`() {
      val id = service.setDeliverableCompletion(deliverable1, inserted.projectId, true)

      assertTableEquals(
          SubmissionsRecord(
              id,
              inserted.projectId,
              deliverable1,
              SubmissionStatus.Completed,
              user.userId,
              clock.instant,
              user.userId,
              clock.instant,
          )
      )
    }

    @Test
    fun `creates a submission with Not Submitted status if isComplete is false`() {
      val id = service.setDeliverableCompletion(deliverable1, inserted.projectId, false)

      assertTableEquals(
          SubmissionsRecord(
              id,
              inserted.projectId,
              deliverable1,
              SubmissionStatus.NotSubmitted,
              user.userId,
              clock.instant,
              user.userId,
              clock.instant,
          )
      )
    }

    @Test
    fun `updates existing submission to Complete status`() {
      insertSubmission(deliverableId = deliverable1, submissionStatus = SubmissionStatus.NotNeeded)
      val existing = dslContext.fetchSingle(SUBMISSIONS)
      service.setDeliverableCompletion(deliverable1, inserted.projectId, true)
      assertTableEquals(existing.apply { submissionStatusId = SubmissionStatus.Completed })
    }

    @Test
    fun `updates existing submission to NotSubmitted status if isComplete is false`() {
      insertSubmission(deliverableId = deliverable1, submissionStatus = SubmissionStatus.NotNeeded)
      val existing = dslContext.fetchSingle(SUBMISSIONS)
      service.setDeliverableCompletion(deliverable1, inserted.projectId, false)
      assertTableEquals(existing.apply { submissionStatusId = SubmissionStatus.NotSubmitted })
    }

    @Test
    fun `updates application module status according to deliverable completion status`() {
      assertTableEquals(
          ApplicationModulesRecord(
              inserted.applicationId,
              inserted.moduleId,
              ApplicationModuleStatus.Incomplete,
          ),
          "0/2 completed deliverables",
      )

      service.setDeliverableCompletion(deliverable1, inserted.projectId, true)

      assertTableEquals(
          ApplicationModulesRecord(
              inserted.applicationId,
              inserted.moduleId,
              ApplicationModuleStatus.Incomplete,
          ),
          "1/2 completed deliverables",
      )

      service.setDeliverableCompletion(deliverable2, inserted.projectId, true)

      assertTableEquals(
          ApplicationModulesRecord(
              inserted.applicationId,
              inserted.moduleId,
              ApplicationModuleStatus.Complete,
          ),
          "2/2 completed deliverables",
      )

      service.setDeliverableCompletion(deliverable2, inserted.projectId, false)

      assertTableEquals(
          ApplicationModulesRecord(
              inserted.applicationId,
              inserted.moduleId,
              ApplicationModuleStatus.Incomplete,
          ),
          "1/2 completed deliverables after un-submitting a deliverable",
      )
    }
  }

  @Nested
  inner class SubmitDeliverable {
    @Test
    fun `creates a submission with In Review status`() {
      val id = service.submitDeliverable(deliverable1, inserted.projectId)

      assertTableEquals(
          SubmissionsRecord(
              id,
              inserted.projectId,
              deliverable1,
              SubmissionStatus.InReview,
              user.userId,
              clock.instant,
              user.userId,
              clock.instant,
          )
      )
    }

    @Test
    fun `updates existing submission to In Review status`() {
      insertSubmission(deliverableId = deliverable1, submissionStatus = SubmissionStatus.NotNeeded)
      val existing = dslContext.fetchSingle(SUBMISSIONS)
      service.submitDeliverable(deliverable1, inserted.projectId)
      assertTableEquals(existing.apply { submissionStatusId = SubmissionStatus.InReview })
    }

    @Test
    fun `publishes event for a new questionnaire deliverable submission`() {
      val deliverableId = insertDeliverable(deliverableTypeId = DeliverableType.Questions)

      service.submitDeliverable(deliverableId, inserted.projectId)
      eventPublisher.assertEventPublished(
          QuestionsDeliverableSubmittedEvent(deliverableId, inserted.projectId)
      )
    }

    @Test
    fun `publishes event for submitting an existing questionnaire deliverable`() {
      val deliverableId = insertDeliverable(deliverableTypeId = DeliverableType.Questions)
      insertSubmission(
          deliverableId = deliverableId,
          submissionStatus = SubmissionStatus.NotSubmitted,
      )

      service.submitDeliverable(deliverableId, inserted.projectId)
      eventPublisher.assertEventPublished(
          QuestionsDeliverableSubmittedEvent(deliverableId, inserted.projectId)
      )
    }

    @Test
    fun `does not publish event for submitting a non-questionnaire deliverable`() {
      val deliverableId = insertDeliverable(deliverableTypeId = DeliverableType.Document)
      insertSubmission(
          deliverableId = deliverableId,
          submissionStatus = SubmissionStatus.NotSubmitted,
      )

      service.submitDeliverable(deliverableId, inserted.projectId)
      eventPublisher.assertEventNotPublished<QuestionsDeliverableSubmittedEvent>()
    }
  }
}
