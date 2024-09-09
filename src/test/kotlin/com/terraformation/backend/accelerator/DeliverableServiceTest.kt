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
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationModulesRow
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionsRow
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
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
            organizationsDao),
        DeliverableStore(dslContext),
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
        inserted.applicationId, inserted.moduleId, ApplicationModuleStatus.Incomplete)

    deliverable1 = insertDeliverable(isRequired = true)
    deliverable2 = insertDeliverable(isRequired = true)

    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectDeliverables(any()) } returns true
    every { user.canReadModule(any()) } returns true
    every { user.canCreateSubmission(any()) } returns true
  }

  @Test
  fun `creates a submission with Complete status`() {
    val id = service.completeDeliverable(deliverable1, inserted.projectId)

    assertEquals(
        SubmissionsRow(
            id,
            inserted.projectId,
            deliverable1,
            SubmissionStatus.Completed,
            user.userId,
            clock.instant,
            user.userId,
            clock.instant,
        ),
        submissionsDao.fetchOneById(id))
  }

  @Test
  fun `updates existing submission to Complete status`() {
    insertSubmission(deliverableId = deliverable1, submissionStatus = SubmissionStatus.NotNeeded)
    val existing = submissionsDao.fetchOneById(inserted.submissionId)!!
    service.completeDeliverable(deliverable1, inserted.projectId)
    assertEquals(
        existing.copy(submissionStatusId = SubmissionStatus.Completed),
        submissionsDao.fetchOneById(inserted.submissionId))
  }

  @Test
  fun `updates application module status to complete if all required deliverables completed`() {
    assertEquals(
        listOf(
            ApplicationModulesRow(
                inserted.applicationId, inserted.moduleId, ApplicationModuleStatus.Incomplete)),
        applicationModulesDao.findAll(),
        "0/2 completed deliverables")

    service.completeDeliverable(deliverable1, inserted.projectId)

    assertEquals(
        listOf(
            ApplicationModulesRow(
                inserted.applicationId, inserted.moduleId, ApplicationModuleStatus.Incomplete)),
        applicationModulesDao.findAll(),
        "1/2 completed deliverables")

    service.completeDeliverable(deliverable2, inserted.projectId)

    assertEquals(
        listOf(
            ApplicationModulesRow(
                inserted.applicationId, inserted.moduleId, ApplicationModuleStatus.Complete)),
        applicationModulesDao.findAll(),
        "2/2 completed deliverables")
  }
}
