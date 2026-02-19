package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.DeliverableDueDateModel
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverableProjectDueDatesRow
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_PROJECT_DUE_DATES
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class DeliverableDueDateStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: DeliverableDueDateStore by lazy { DeliverableDueDateStore(dslContext) }

  @BeforeEach
  fun setUp() {
    insertOrganization()

    every { user.canReadAllDeliverables() } returns true
    every { user.canManageDeliverables() } returns true
  }

  @Nested
  inner class FetchDeliverableDueDates {
    @Test
    fun `can filter by IDs`() {
      val projectId1 = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val projectId2 = insertProject(phase = CohortPhase.Phase1FeasibilityStudy)

      val moduleId1 = insertModule()
      val moduleId2 = insertModule()

      val project1Module1EndDate = LocalDate.of(2024, 1, 15)
      val project1Module2EndDate = LocalDate.of(2024, 2, 15)
      val project2Module2EndDate = LocalDate.of(2024, 3, 15)

      insertProjectModule(
          projectId = projectId1,
          moduleId = moduleId1,
          startDate = project1Module1EndDate.minusDays(1),
          endDate = project1Module1EndDate,
      )

      insertProjectModule(
          projectId = projectId1,
          moduleId = moduleId2,
          startDate = project1Module2EndDate.minusDays(1),
          endDate = project1Module2EndDate,
      )

      insertProjectModule(
          projectId = projectId2,
          moduleId = moduleId2,
          startDate = project2Module2EndDate.minusDays(1),
          endDate = project2Module2EndDate,
      )

      val deliverableId1 = insertDeliverable(moduleId = moduleId1)
      val deliverableId2 =
          insertDeliverable(moduleId = moduleId1) // Has project and cohort overrides

      val deliverableId3 = insertDeliverable(moduleId = moduleId2)
      val deliverableId4 = insertDeliverable(moduleId = moduleId2)

      val projectDeliverableDueDate = LocalDate.of(2024, 5, 15)
      insertDeliverableProjectDueDate(deliverableId2, projectId1, projectDeliverableDueDate)

      val project1Deliverable1 =
          DeliverableDueDateModel(
              projectId = projectId1,
              deliverableId = deliverableId1,
              moduleId = moduleId1,
              moduleDueDate = project1Module1EndDate,
              projectDueDate = null,
          )

      val project1Deliverable2 =
          project1Deliverable1.copy(
              deliverableId = deliverableId2,
              projectDueDate = projectDeliverableDueDate,
          )

      val project1Deliverable3 =
          project1Deliverable1.copy(
              deliverableId = deliverableId3,
              moduleId = moduleId2,
              moduleDueDate = project1Module2EndDate,
          )

      val project1Deliverable4 =
          project1Deliverable3.copy(
              deliverableId = deliverableId4,
          )

      val project2Deliverable3 =
          project1Deliverable3.copy(
              projectId = projectId2,
              moduleDueDate = project2Module2EndDate,
          )

      val project2Deliverable4 =
          project2Deliverable3.copy(
              deliverableId = deliverableId4,
          )

      assertSetEquals(
          setOf(
              project1Deliverable1,
              project1Deliverable2,
              project1Deliverable3,
              project1Deliverable4,
              project2Deliverable3,
              project2Deliverable4,
          ),
          store.fetchDeliverableDueDates().toSet(),
          "Fetch with no filters",
      )

      assertSetEquals(
          setOf(
              project1Deliverable1,
              project1Deliverable2,
              project1Deliverable3,
              project1Deliverable4,
          ),
          store.fetchDeliverableDueDates(projectId = projectId1).toSet(),
          "Fetch with cohort filter",
      )

      assertSetEquals(
          setOf(
              project1Deliverable3,
              project1Deliverable4,
              project2Deliverable3,
              project2Deliverable4,
          ),
          store.fetchDeliverableDueDates(moduleId = moduleId2).toSet(),
          "Fetch with module filter",
      )

      assertSetEquals(
          setOf(
              project1Deliverable3,
              project1Deliverable4,
          ),
          store.fetchDeliverableDueDates(projectId = projectId1, moduleId = moduleId2).toSet(),
          "Fetch with cohort and module filter",
      )

      assertSetEquals(
          setOf(
              project1Deliverable3,
              project2Deliverable3,
          ),
          store.fetchDeliverableDueDates(deliverableId = deliverableId3).toSet(),
          "Fetch with deliverable filter",
      )

      assertSetEquals(
          setOf(
              project1Deliverable3,
          ),
          store
              .fetchDeliverableDueDates(projectId = projectId1, deliverableId = deliverableId3)
              .toSet(),
          "Fetch with cohort and deliverable filter",
      )

      assertSetEquals(
          emptySet(),
          store
              .fetchDeliverableDueDates(deliverableId = deliverableId1, moduleId = moduleId2)
              .toSet(),
          "Fetch with conflicting deliverable and module filter",
      )
    }

    @Test
    fun `throws exception if no permission to read all deliverables`() {
      every { user.canReadAllDeliverables() } returns false

      insertModule()
      insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule()
      insertDeliverable()

      assertThrows<AccessDeniedException> { store.fetchDeliverableDueDates() }
    }
  }

  @Nested
  inner class UpsertDeliverableProjectDueDate {
    @Test
    fun `inserts or updates deliverable project due date`() {
      insertModule()
      insertDeliverable()

      val projectToUpdate = insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule()
      val projectToInsert = insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule()

      insertDeliverableProjectDueDate(
          inserted.deliverableId,
          projectToUpdate,
          LocalDate.of(2024, 5, 1),
      )

      val existingRow = deliverableProjectDueDatesDao.findAll().firstOrNull()

      store.upsertDeliverableProjectDueDate(
          inserted.deliverableId,
          projectToUpdate,
          LocalDate.of(2024, 6, 1),
      )
      store.upsertDeliverableProjectDueDate(
          inserted.deliverableId,
          projectToInsert,
          LocalDate.of(2024, 7, 1),
      )

      val updatedRow = existingRow?.copy(dueDate = LocalDate.of(2024, 6, 1))
      val insertedRow =
          DeliverableProjectDueDatesRow(
              projectId = projectToInsert,
              deliverableId = inserted.deliverableId,
              dueDate = LocalDate.of(2024, 7, 1),
          )

      assertSetEquals(
          setOf(updatedRow, insertedRow),
          deliverableProjectDueDatesDao.findAll().toSet(),
      )
    }

    @Test
    fun `throws exception if no permission to manage deliverables`() {
      every { user.canManageDeliverables() } returns false

      insertModule()
      insertDeliverable()

      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule()

      assertThrows<AccessDeniedException> {
        store.upsertDeliverableProjectDueDate(
            inserted.deliverableId,
            projectId,
            LocalDate.of(2024, 5, 1),
        )
      }
    }
  }

  @Nested
  inner class DeleteDeliverableProjectDueDate {
    @Test
    fun `deletes deliverable project due date`() {
      insertModule()
      insertDeliverable()

      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule()

      insertDeliverableProjectDueDate(
          inserted.deliverableId,
          inserted.projectId,
          LocalDate.of(2024, 5, 1),
      )

      assertEquals(
          listOf(
              DeliverableProjectDueDatesRow(
                  inserted.deliverableId,
                  inserted.projectId,
                  LocalDate.of(2024, 5, 1),
              )
          ),
          deliverableProjectDueDatesDao.findAll(),
          "Row exists before deletion",
      )

      store.deleteDeliverableProjectDueDate(inserted.deliverableId, projectId)

      assertTableEmpty(DELIVERABLE_PROJECT_DUE_DATES, "Row no longer exists after deletion")
    }

    @Test
    fun `throws exception if no permission to manage deliverables`() {
      every { user.canManageDeliverables() } returns false

      insertModule()
      insertDeliverable()

      val projectId = insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertProjectModule()

      insertDeliverableProjectDueDate(
          inserted.deliverableId,
          inserted.projectId,
          LocalDate.of(2024, 5, 1),
      )
      assertThrows<AccessDeniedException> {
        store.deleteDeliverableProjectDueDate(inserted.deliverableId, projectId)
      }
    }
  }
}
