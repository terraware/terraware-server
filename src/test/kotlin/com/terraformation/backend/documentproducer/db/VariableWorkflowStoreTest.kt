package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableReviewedEvent
import com.terraformation.backend.documentproducer.model.ExistingVariableWorkflowHistoryModel
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class VariableWorkflowStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store by lazy { VariableWorkflowStore(clock, dslContext, eventPublisher) }

  private val stableId = "${UUID.randomUUID()}"

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertApplication()
    insertDocumentTemplate()
    insertVariableManifest()
    insertDocument()
    insertModule()
    insertDeliverable()
    insertVariableManifestEntry(
        insertTextVariable(deliverableId = inserted.deliverableId, stableId = stableId)
    )

    insertValue(variableId = inserted.variableId)

    insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
  }

  @Nested
  inner class FetchCurrentForProject {
    @Test
    fun `returns latest workflow details from previous versions of variables if none on current versions`() {
      val originalVariableId1 = inserted.variableId

      insertVariableWorkflowHistory(
          createdTime = Instant.EPOCH,
          status = VariableWorkflowStatus.NotSubmitted,
      )

      val currentHistoryId1 =
          insertVariableWorkflowHistory(
              createdTime = Instant.EPOCH.plusSeconds(1),
              feedback = "feedback 1",
              status = VariableWorkflowStatus.InReview,
          )

      val newVariableId1 =
          insertTextVariable(
              insertVariable(
                  deliverableId = inserted.deliverableId,
                  replacesVariableId = originalVariableId1,
                  stableId = stableId,
                  type = VariableType.Text,
              )
          )

      val stableId2 = "$stableId-2"
      val originalVariableId2 = insertTextVariable(stableId = stableId2)

      val currentHistoryId2 =
          insertVariableWorkflowHistory(
              createdTime = Instant.EPOCH,
              feedback = "feedback 2",
              status = VariableWorkflowStatus.InReview,
          )

      val newVariableId2 =
          insertTextVariable(
              insertVariable(
                  replacesVariableId = originalVariableId2,
                  stableId = stableId2,
                  type = VariableType.Text,
              )
          )

      val stableId3 = "$stableId-3"
      val originalVariableId3 = insertTextVariable(stableId = stableId3)

      insertVariableWorkflowHistory(
          createdTime = Instant.EPOCH,
          status = VariableWorkflowStatus.NotSubmitted,
      )

      val newVariableId3 =
          insertTextVariable(
              insertVariable(
                  replacesVariableId = originalVariableId3,
                  stableId = stableId3,
                  type = VariableType.Text,
              )
          )

      val currentHistoryId3 =
          insertVariableWorkflowHistory(
              createdTime = Instant.EPOCH.plusSeconds(2),
              feedback = "feedback 3",
              internalComment = "internal 3",
              status = VariableWorkflowStatus.Approved,
          )

      val expected =
          mapOf(
              newVariableId1 to
                  ExistingVariableWorkflowHistoryModel(
                      createdBy = user.userId,
                      createdTime = Instant.EPOCH.plusSeconds(1),
                      feedback = "feedback 1",
                      id = currentHistoryId1,
                      internalComment = null,
                      maxVariableValueId = inserted.variableValueId,
                      originalVariableId = originalVariableId1,
                      projectId = inserted.projectId,
                      status = VariableWorkflowStatus.InReview,
                      variableId = newVariableId1,
                  ),
              newVariableId2 to
                  ExistingVariableWorkflowHistoryModel(
                      createdBy = user.userId,
                      createdTime = Instant.EPOCH,
                      feedback = "feedback 2",
                      id = currentHistoryId2,
                      internalComment = null,
                      maxVariableValueId = inserted.variableValueId,
                      originalVariableId = originalVariableId2,
                      projectId = inserted.projectId,
                      status = VariableWorkflowStatus.InReview,
                      variableId = newVariableId2,
                  ),
              newVariableId3 to
                  ExistingVariableWorkflowHistoryModel(
                      createdBy = user.userId,
                      createdTime = Instant.EPOCH.plusSeconds(2),
                      feedback = "feedback 3",
                      id = currentHistoryId3,
                      internalComment = "internal 3",
                      maxVariableValueId = inserted.variableValueId,
                      // This history entry is from the current version of the variable
                      originalVariableId = newVariableId3,
                      projectId = inserted.projectId,
                      status = VariableWorkflowStatus.Approved,
                      variableId = newVariableId3,
                  ),
          )

      assertEquals(expected, store.fetchCurrentForProject(inserted.projectId))
    }

    @Test
    fun `does not populate internal comment if user lacks permission to see it`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertOrganizationUser(role = Role.Manager)

      val historyId =
          insertVariableWorkflowHistory(
              feedback = "feedback",
              internalComment = "internal comment",
              status = VariableWorkflowStatus.InReview,
          )

      val expected =
          mapOf(
              inserted.variableId to
                  ExistingVariableWorkflowHistoryModel(
                      createdBy = user.userId,
                      createdTime = Instant.EPOCH,
                      feedback = "feedback",
                      id = historyId,
                      internalComment = null,
                      maxVariableValueId = inserted.variableValueId,
                      originalVariableId = inserted.variableId,
                      projectId = inserted.projectId,
                      status = VariableWorkflowStatus.InReview,
                      variableId = inserted.variableId,
                  )
          )

      assertEquals(expected, store.fetchCurrentForProject(inserted.projectId))
    }

    @Test
    fun `throws exception if no permission to read project`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      assertThrows<ProjectNotFoundException> { store.fetchCurrentForProject(inserted.projectId) }
    }
  }

  @Nested
  inner class FetchProjectVariableHistory {
    @Test
    fun `returns list of workflow details sorted by createdTime`() {
      val originalVariableId = inserted.variableId

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

      val newVariableId =
          insertTextVariable(
              insertVariable(
                  deliverableId = inserted.deliverableId,
                  replacesVariableId = originalVariableId,
                  stableId = stableId,
                  type = VariableType.Text,
              )
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
                  originalVariableId = newVariableId,
                  projectId = inserted.projectId,
                  status = VariableWorkflowStatus.Approved,
                  variableId = newVariableId,
              ),
              ExistingVariableWorkflowHistoryModel(
                  createdBy = user.userId,
                  createdTime = clock.instant.plusSeconds(600),
                  feedback = "old feedback",
                  id = oldWorkflowId,
                  internalComment = "old comment",
                  maxVariableValueId = inserted.variableValueId,
                  originalVariableId = originalVariableId,
                  projectId = inserted.projectId,
                  status = VariableWorkflowStatus.InReview,
                  variableId = newVariableId,
              ),
              ExistingVariableWorkflowHistoryModel(
                  createdBy = user.userId,
                  createdTime = clock.instant.plusSeconds(300),
                  feedback = null,
                  id = oldestWorkflowId,
                  internalComment = null,
                  maxVariableValueId = inserted.variableValueId,
                  originalVariableId = originalVariableId,
                  projectId = inserted.projectId,
                  status = VariableWorkflowStatus.NotSubmitted,
                  variableId = newVariableId,
              ),
          ),
          store.fetchProjectVariableHistory(inserted.projectId, newVariableId),
      )
    }

    @Test
    fun `throws exception if no permission to read internal workflow details`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertOrganizationUser(role = Role.Admin)

      assertThrows<AccessDeniedException> {
        store.fetchProjectVariableHistory(inserted.projectId, inserted.variableId)
      }
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `publishes event for the first workflow action`() {
      store.update(
          projectId = inserted.projectId,
          variableId = inserted.variableId,
      ) {
        it.copy(
            status = VariableWorkflowStatus.Approved,
            feedback = null,
            internalComment = null,
        )
      }

      eventPublisher.assertEventPublished(
          QuestionsDeliverableReviewedEvent(inserted.deliverableId, inserted.projectId)
      )
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
      ) {
        it.copy(
            status = VariableWorkflowStatus.Approved,
            feedback = null,
            internalComment = null,
        )
      }

      eventPublisher.assertEventPublished(
          QuestionsDeliverableReviewedEvent(inserted.deliverableId, inserted.projectId)
      )
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
      ) {
        it.copy(
            status = VariableWorkflowStatus.Rejected,
            feedback = "Unchanged feedback",
            internalComment = "New internal comment",
        )
      }

      eventPublisher.assertEventNotPublished<QuestionsDeliverableReviewedEvent>()
    }

    @Test
    fun `does not publish event if a deliverable is not associated`() {
      val nonDeliverableVariable =
          insertVariableManifestEntry(
              insertTextVariable(
                  id = insertVariable(deliverableId = null, type = VariableType.Text)
              )
          )

      insertValue(variableId = nonDeliverableVariable)

      store.update(
          projectId = inserted.projectId,
          variableId = nonDeliverableVariable,
      ) {
        it.copy(
            status = VariableWorkflowStatus.Approved,
        )
      }

      eventPublisher.assertEventNotPublished<QuestionsDeliverableReviewedEvent>()
    }

    @Test
    fun `throws exception for non-admin`() {
      val variableId = inserted.variableId
      insertVariableWorkflowHistory(status = VariableWorkflowStatus.InReview)

      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertOrganizationUser(role = Role.Admin)

      assertThrows<AccessDeniedException> {
        store.update(
            projectId = inserted.projectId,
            variableId = variableId,
        ) {
          it
        }
      }
    }
  }
}
