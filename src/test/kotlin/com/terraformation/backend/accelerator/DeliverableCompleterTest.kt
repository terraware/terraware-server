package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.event.DeliverableDocumentUploadedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedEvent
import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationModuleStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionsRow
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DeliverableCompleterTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val completer: DeliverableCompleter by lazy {
    DeliverableCompleter(
        ApplicationStore(
            clock,
            countriesDao,
            TestSingletons.countryDetector,
            dslContext,
            eventPublisher,
            Messages(),
        ),
        DeliverableStore(dslContext),
        ModuleStore(dslContext),
        SubmissionStore(clock, dslContext, eventPublisher),
        SystemUser(usersDao),
    )
  }

  private lateinit var applicationId: ApplicationId
  private lateinit var applicationModuleId: ModuleId
  private lateinit var organizationId: OrganizationId
  private lateinit var preScreenModuleId: ModuleId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(role = Role.Admin)

    projectId = insertProject()
    applicationId = insertApplication()

    applicationModuleId = insertModule(phase = CohortPhase.Application)
    preScreenModuleId = insertModule(phase = CohortPhase.PreScreen)

    every { user.adminOrganizations() } returns setOf(organizationId)
    every { user.canCreateSubmission(projectId) } returns true
    every { user.canReadApplication(applicationId) } returns true
    every { user.canReadProject(projectId) } returns true
    every { user.canReadProjectDeliverables(projectId) } returns true
    every { user.canUpdateApplicationSubmissionStatus(applicationId) } returns true
  }

  @Nested
  inner class OnDeliverableDocumentUploadedEvent {
    private lateinit var deliverableId: DeliverableId

    @BeforeEach
    fun setUp() {
      deliverableId =
          insertDeliverable(
              deliverableTypeId = DeliverableType.Document,
              moduleId = applicationModuleId,
              isRequired = true,
          )
      insertApplicationModule(applicationId, applicationModuleId)
    }

    @Test
    fun `updates deliverable status`() {
      val otherDeliverableId = insertDeliverable(moduleId = applicationModuleId)
      insertSubmission()

      completer.on(
          DeliverableDocumentUploadedEvent(deliverableId, SubmissionDocumentId(1), projectId)
      )

      assertEquals(
          mapOf(
              deliverableId to SubmissionStatus.Completed,
              otherDeliverableId to SubmissionStatus.NotSubmitted,
          ),
          submissionsDao.fetchByProjectId(projectId).associate {
            it.deliverableId to it.submissionStatusId
          },
      )
    }

    @Test
    fun `updates module status if all required deliverables completed`() {
      insertDeliverable(
          deliverableTypeId = DeliverableType.Document,
          moduleId = applicationModuleId,
          isRequired = false,
      )
      insertDeliverable(
          deliverableTypeId = DeliverableType.Document,
          moduleId = applicationModuleId,
          isRequired = true,
      )
      insertSubmission(submissionStatus = SubmissionStatus.Completed, projectId = projectId)

      completer.on(
          DeliverableDocumentUploadedEvent(deliverableId, SubmissionDocumentId(1), projectId)
      )

      assertEquals(
          ApplicationModuleStatus.Complete,
          applicationModulesDao
              .fetchByModuleId(applicationModuleId)
              .single()
              .applicationModuleStatusId,
      )
    }

    @Test
    fun `does not update module status if some required deliverables not completed`() {
      insertDeliverable(
          deliverableTypeId = DeliverableType.Document,
          moduleId = applicationModuleId,
          isRequired = true,
      )

      completer.on(
          DeliverableDocumentUploadedEvent(deliverableId, SubmissionDocumentId(1), projectId)
      )

      assertEquals(
          ApplicationModuleStatus.Incomplete,
          applicationModulesDao
              .fetchByModuleId(applicationModuleId)
              .single()
              .applicationModuleStatusId,
      )
    }

    @Test
    fun `does not update deliverable status for non-application phase`() {
      insertModule(phase = CohortPhase.Phase0DueDiligence)
      val otherModuleDeliverableId = insertDeliverable()

      completer.on(
          DeliverableDocumentUploadedEvent(
              otherModuleDeliverableId,
              SubmissionDocumentId(1),
              projectId,
          )
      )

      assertEquals(
          emptyList<SubmissionsRow>(),
          submissionsDao.fetchByDeliverableId(otherModuleDeliverableId),
      )
    }
  }

  @Nested
  inner class OnParticipantProjectSpeciesAddedEvent {
    private lateinit var deliverableId: DeliverableId

    @BeforeEach
    fun setUp() {
      deliverableId =
          insertDeliverable(
              deliverableTypeId = DeliverableType.Species,
              moduleId = applicationModuleId,
          )
      insertApplicationModule(applicationId, applicationModuleId)
    }

    @Test
    fun `updates deliverable status`() {
      completer.on(
          ParticipantProjectSpeciesAddedEvent(
              deliverableId,
              ExistingParticipantProjectSpeciesModel(
                  id = ParticipantProjectSpeciesId(1),
                  projectId = projectId,
                  speciesId = SpeciesId(1),
              ),
          )
      )

      assertEquals(
          SubmissionStatus.Completed,
          submissionsDao.fetchByProjectId(projectId).single().submissionStatusId,
      )
    }
  }
}
