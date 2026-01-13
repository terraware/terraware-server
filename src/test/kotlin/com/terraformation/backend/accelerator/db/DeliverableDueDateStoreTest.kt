package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.DeliverableDueDateModel
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverableCohortDueDatesRow
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverableProjectDueDatesRow
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_COHORT_DUE_DATES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_PROJECT_DUE_DATES
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
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
      val cohortId1 = insertCohort()
      val cohortId2 = insertCohort()
      val projectId1 = insertProject(cohortId = cohortId1)

      val moduleId1 = insertModule()
      val moduleId2 = insertModule()

      val cohort1Module1EndDate = LocalDate.of(2024, 1, 15)
      val cohort1Module2EndDate = LocalDate.of(2024, 2, 15)
      val cohort2Module2EndDate = LocalDate.of(2024, 3, 15)

      insertCohortModule(
          cohortId = cohortId1,
          moduleId = moduleId1,
          startDate = cohort1Module1EndDate.minusDays(1),
          endDate = cohort1Module1EndDate,
      )

      insertCohortModule(
          cohortId = cohortId1,
          moduleId = moduleId2,
          startDate = cohort1Module2EndDate.minusDays(1),
          endDate = cohort1Module2EndDate,
      )

      insertCohortModule(
          cohortId = cohortId2,
          moduleId = moduleId2,
          startDate = cohort2Module2EndDate.minusDays(1),
          endDate = cohort2Module2EndDate,
      )

      val deliverableId1 = insertDeliverable(moduleId = moduleId1)
      val deliverableId2 =
          insertDeliverable(moduleId = moduleId1) // Has project and cohort overrides

      val deliverableId3 = insertDeliverable(moduleId = moduleId2)
      val deliverableId4 = insertDeliverable(moduleId = moduleId2)

      val cohortDeliverableDueDate = LocalDate.of(2024, 4, 15)
      insertDeliverableCohortDueDate(deliverableId2, cohortId1, cohortDeliverableDueDate)

      val projectDeliverableDueDate = LocalDate.of(2024, 5, 15)
      insertDeliverableProjectDueDate(deliverableId2, projectId1, projectDeliverableDueDate)

      val cohort1Deliverable1 =
          DeliverableDueDateModel(
              cohortId = cohortId1,
              deliverableId = deliverableId1,
              moduleId = moduleId1,
              moduleDueDate = cohort1Module1EndDate,
              projectDueDates = emptyMap(),
          )

      val cohort1Deliverable2 =
          cohort1Deliverable1.copy(
              deliverableId = deliverableId2,
              cohortDueDate = cohortDeliverableDueDate,
              projectDueDates = mapOf(projectId1 to projectDeliverableDueDate),
          )

      val cohort1Deliverable3 =
          cohort1Deliverable1.copy(
              deliverableId = deliverableId3,
              moduleId = moduleId2,
              moduleDueDate = cohort1Module2EndDate,
          )

      val cohort1Deliverable4 =
          cohort1Deliverable3.copy(
              deliverableId = deliverableId4,
          )

      val cohort2Deliverable3 =
          cohort1Deliverable3.copy(
              cohortId = cohortId2,
              moduleDueDate = cohort2Module2EndDate,
          )

      val cohort2Deliverable4 =
          cohort2Deliverable3.copy(
              deliverableId = deliverableId4,
          )

      assertSetEquals(
          setOf(
              cohort1Deliverable1,
              cohort1Deliverable2,
              cohort1Deliverable3,
              cohort1Deliverable4,
              cohort2Deliverable3,
              cohort2Deliverable4,
          ),
          store.fetchDeliverableDueDates().toSet(),
          "Fetch with no filters",
      )

      assertSetEquals(
          setOf(
              cohort1Deliverable1,
              cohort1Deliverable2,
              cohort1Deliverable3,
              cohort1Deliverable4,
          ),
          store.fetchDeliverableDueDates(cohortId = cohortId1).toSet(),
          "Fetch with cohort filter",
      )

      assertSetEquals(
          setOf(cohort1Deliverable3, cohort1Deliverable4, cohort2Deliverable3, cohort2Deliverable4),
          store.fetchDeliverableDueDates(moduleId = moduleId2).toSet(),
          "Fetch with module filter",
      )

      assertSetEquals(
          setOf(
              cohort1Deliverable3,
              cohort1Deliverable4,
          ),
          store.fetchDeliverableDueDates(cohortId = cohortId1, moduleId = moduleId2).toSet(),
          "Fetch with cohort and module filter",
      )

      assertSetEquals(
          setOf(
              cohort1Deliverable3,
              cohort2Deliverable3,
          ),
          store.fetchDeliverableDueDates(deliverableId = deliverableId3).toSet(),
          "Fetch with deliverable filter",
      )

      assertSetEquals(
          setOf(
              cohort1Deliverable3,
          ),
          store
              .fetchDeliverableDueDates(cohortId = cohortId1, deliverableId = deliverableId3)
              .toSet(),
          "Fetch with cohort and deliverable filter",
      )

      assertSetEquals(
          emptySet<DeliverableDueDateModel>(),
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
      insertCohort()
      insertCohortModule()
      insertDeliverable()

      assertThrows<AccessDeniedException> { store.fetchDeliverableDueDates() }
    }
  }

  @Nested
  inner class UpsertDeliverableCohortDueDate {
    @Test
    fun `inserts or updates deliverable cohort due date`() {
      insertModule()
      val cohortToUpdate = insertCohort()
      val cohortToInsert = insertCohort()
      insertCohortModule(cohortToUpdate, inserted.moduleId)
      insertCohortModule(cohortToInsert, inserted.moduleId)
      insertDeliverable()

      insertDeliverableCohortDueDate(
          inserted.deliverableId,
          cohortToUpdate,
          LocalDate.of(2024, 5, 1),
      )

      val existingRow = deliverableCohortDueDatesDao.findAll().firstOrNull()

      store.upsertDeliverableCohortDueDate(
          inserted.deliverableId,
          cohortToUpdate,
          LocalDate.of(2024, 6, 1),
      )
      store.upsertDeliverableCohortDueDate(
          inserted.deliverableId,
          cohortToInsert,
          LocalDate.of(2024, 7, 1),
      )

      val updatedRow = existingRow?.copy(dueDate = LocalDate.of(2024, 6, 1))
      val insertedRow =
          DeliverableCohortDueDatesRow(
              cohortId = cohortToInsert,
              deliverableId = inserted.deliverableId,
              dueDate = LocalDate.of(2024, 7, 1),
          )

      assertSetEquals(
          setOf(updatedRow, insertedRow),
          deliverableCohortDueDatesDao.findAll().toSet(),
      )
    }

    @Test
    fun `throws exception if no permission to manage deliverables`() {
      every { user.canManageDeliverables() } returns false

      insertModule()
      insertCohort()
      insertCohortModule()
      insertDeliverable()

      assertThrows<AccessDeniedException> {
        store.upsertDeliverableCohortDueDate(
            inserted.deliverableId,
            inserted.cohortId,
            LocalDate.of(2024, 5, 1),
        )
      }
    }
  }

  @Nested
  inner class UpsertDeliverableProjectDueDate {
    @Test
    fun `inserts or updates deliverable project due date`() {
      insertModule()
      insertCohort()
      insertCohortModule(inserted.cohortId, inserted.moduleId)
      insertDeliverable()

      val projectToUpdate = insertProject(cohortId = inserted.cohortId)
      val projectToInsert = insertProject(cohortId = inserted.cohortId)

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
      insertCohort()
      insertCohortModule()
      insertDeliverable()

      val projectId = insertProject(cohortId = inserted.cohortId)

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
  inner class DeleteDeliverableCohortDueDate {
    @Test
    fun `deletes deliverable cohort due date`() {
      insertModule()
      insertCohort()
      insertCohortModule()
      insertDeliverable()

      insertDeliverableCohortDueDate(
          inserted.deliverableId,
          inserted.cohortId,
          LocalDate.of(2024, 5, 1),
      )

      assertEquals(
          listOf(
              DeliverableCohortDueDatesRow(
                  inserted.deliverableId,
                  inserted.cohortId,
                  LocalDate.of(2024, 5, 1),
              )
          ),
          deliverableCohortDueDatesDao.findAll(),
          "Row exists before deletion",
      )

      store.deleteDeliverableCohortDueDate(inserted.deliverableId, inserted.cohortId)

      assertTableEmpty(DELIVERABLE_COHORT_DUE_DATES, "Row no longer exists after deletion")
    }

    @Test
    fun `throws exception if no permission to manage deliverables`() {
      every { user.canManageDeliverables() } returns false

      insertModule()
      insertCohort()
      insertCohortModule()
      insertDeliverable()

      insertDeliverableCohortDueDate(
          inserted.deliverableId,
          inserted.cohortId,
          LocalDate.of(2024, 5, 1),
      )
      assertThrows<AccessDeniedException> {
        store.deleteDeliverableCohortDueDate(inserted.deliverableId, inserted.cohortId)
      }
    }
  }

  @Nested
  inner class DeleteDeliverableProjectDueDate {
    @Test
    fun `deletes deliverable project due date`() {
      insertModule()
      insertCohort()
      insertCohortModule()
      insertDeliverable()

      val projectId = insertProject(cohortId = inserted.cohortId)

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
      insertCohort()
      insertCohortModule()
      insertDeliverable()

      val projectId = insertProject(cohortId = inserted.cohortId)

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
