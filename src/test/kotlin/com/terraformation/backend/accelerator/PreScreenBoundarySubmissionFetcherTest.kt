package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.accelerator.model.SubmissionDocumentModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class PreScreenBoundarySubmissionFetcherTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val fetcher: PreScreenBoundarySubmissionFetcher by lazy {
    PreScreenBoundarySubmissionFetcher(
        ApplicationStore(clock, countriesDao, mockk(), dslContext, mockk(), mockk()),
        deliverableId,
    )
  }

  private lateinit var deliverableId: DeliverableId
  private lateinit var initialSubmissionModel: DeliverableSubmissionModel

  @BeforeEach
  fun setUp() {
    insertOrganization(name = "Org name")
    insertProject(name = "Project name")
    insertApplication(internalName = "XXX_internalName")
    insertModule(name = "Pre-screen module", phase = CohortPhase.PreScreen)
    insertApplicationModule()

    deliverableId =
        insertDeliverable(
            deliverableCategoryId = DeliverableCategory.GIS,
            descriptionHtml = "Deliverable description",
            name = "Pre-screen boundary deliverable",
        )

    initialSubmissionModel =
        DeliverableSubmissionModel(
            category = DeliverableCategory.GIS,
            deliverableId = deliverableId,
            descriptionHtml = "Deliverable description",
            documents = emptyList(),
            dueDate = null,
            feedback = null,
            internalComment = null,
            modifiedTime = null,
            moduleId = inserted.moduleId,
            moduleName = "Pre-screen module",
            moduleTitle = null,
            name = "Pre-screen boundary deliverable",
            organizationId = inserted.organizationId,
            organizationName = "Org name",
            position = 1,
            projectDealName = "XXX_internalName",
            projectId = inserted.projectId,
            projectName = "Project name",
            required = false,
            sensitive = false,
            status = SubmissionStatus.NotSubmitted,
            submissionId = null,
            templateUrl = null,
            type = DeliverableType.Document,
        )

    every { user.canReadProjectDeliverables(any()) } returns true
  }

  @Test
  fun `returns submission with NotSubmitted if project has not submitted a submission`() {
    assertEquals(initialSubmissionModel, fetcher.fetchSubmission(inserted.projectId))
  }

  @Test
  fun `returns submission with documents if project has submitted documents`() {
    val submissionId =
        insertSubmission(
            deliverableId = deliverableId,
            modifiedTime = clock.instant,
            projectId = inserted.projectId,
            submissionStatus = SubmissionStatus.Completed,
        )
    val documentId1 = insertSubmissionDocument()
    val documentId2 = insertSubmissionDocument()

    val expected =
        initialSubmissionModel.copy(
            submissionId = submissionId,
            documents =
                listOf(
                    SubmissionDocumentModel(
                        createdTime = Instant.EPOCH,
                        description = null,
                        documentStore = DocumentStore.Google,
                        id = documentId1,
                        location = "Location 1",
                        name = "Submission Document 1",
                        originalName = "Original Name 1",
                    ),
                    SubmissionDocumentModel(
                        createdTime = Instant.EPOCH,
                        description = null,
                        documentStore = DocumentStore.Google,
                        id = documentId2,
                        location = "Location 2",
                        name = "Submission Document 2",
                        originalName = "Original Name 2",
                    ),
                ),
            modifiedTime = clock.instant,
            status = SubmissionStatus.Completed,
        )
    assertEquals(expected, fetcher.fetchSubmission(inserted.projectId))
  }

  @Test
  fun `returns null for project with no application`() {
    insertProject()
    assertNull(fetcher.fetchSubmission(inserted.projectId))
  }

  @Test
  fun `returns null if application deliverable is not imported`() {
    deliverablesDao.deleteById(deliverableId)
    assertNull(fetcher.fetchSubmission(inserted.projectId))
  }

  @Test
  fun `throws exception if no permission to read project deliverables`() {
    every { user.canReadProjectDeliverables(any()) } returns false
    every { user.canReadProject(any()) } returns true

    assertThrows<AccessDeniedException> { fetcher.fetchSubmission(inserted.projectId) }
  }
}
