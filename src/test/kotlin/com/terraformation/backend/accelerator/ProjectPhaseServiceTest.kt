package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.event.CohortPhaseUpdatedEvent
import com.terraformation.backend.accelerator.event.CohortProjectAddedEvent
import com.terraformation.backend.accelerator.event.CohortProjectRemovedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.tables.records.ProjectModulesRecord
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_MODULES
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ProjectPhaseServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val service: ProjectPhaseService by lazy { ProjectPhaseService(dslContext) }

  @BeforeEach
  fun setUp() {
    insertOrganization()
  }

  @Nested
  inner class OnCohortProjectAddedEvent {
    @Test
    fun `updates phase for cohort projects`() {
      val cohortId = insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertProject(cohortId = cohortId)
      val projectId = insertProject()

      insertCohort()
      insertProject(cohortId = inserted.cohortId)

      val expected =
          dslContext.fetch(PROJECTS).onEach { record ->
            if (record.id == projectId) {
              record.phaseId = CohortPhase.Phase0DueDiligence
            }
          }

      service.on(CohortProjectAddedEvent(user.userId, cohortId, projectId))

      assertTableEquals(expected)
    }

    @Test
    fun `populates project modules based on cohort modules`() {
      val cohortId = insertCohort()
      val projectId = insertProject(cohortId = cohortId)
      val moduleId1 = insertModule()
      insertCohortModule(
          startDate = LocalDate.of(2026, 1, 1),
          endDate = LocalDate.of(2026, 1, 2),
          title = "Module 1",
      )
      insertProjectModule(
          startDate = LocalDate.of(2026, 1, 3),
          endDate = LocalDate.of(2026, 1, 4),
          title = "Module 1 (Project)",
      )
      val moduleId2 = insertModule()
      insertCohortModule(
          startDate = LocalDate.of(2026, 1, 5),
          endDate = LocalDate.of(2026, 1, 6),
          title = "Module 2",
      )

      insertCohort()
      insertModule()
      insertCohortModule()

      service.on(CohortProjectAddedEvent(user.userId, cohortId, projectId))

      assertTableEquals(
          setOf(
              // Existing association with module 1 should remain untouched
              ProjectModulesRecord(
                  projectId,
                  moduleId1,
                  LocalDate.of(2026, 1, 3),
                  LocalDate.of(2026, 1, 4),
                  "Module 1 (Project)",
              ),
              // New record should be inserted from cohort modules
              ProjectModulesRecord(
                  projectId,
                  moduleId2,
                  LocalDate.of(2026, 1, 5),
                  LocalDate.of(2026, 1, 6),
                  "Module 2",
              ),
          )
      )
    }
  }

  @Nested
  inner class OnCohortProjectRemovedEvent {
    @Test
    fun `clears phase for cohort projects`() {
      val cohortId = insertCohort(phase = CohortPhase.Phase1FeasibilityStudy)
      val projectId = insertProject(cohortId = cohortId, phase = CohortPhase.Phase1FeasibilityStudy)
      insertProject(cohortId = cohortId, phase = CohortPhase.Phase1FeasibilityStudy)
      insertProject(cohortId = insertCohort())

      val expected =
          dslContext.fetch(PROJECTS).onEach { record ->
            if (record.id == projectId) {
              record.phaseId = null
            }
          }

      service.on(CohortProjectRemovedEvent(cohortId, projectId, user.userId))

      assertTableEquals(expected)
    }

    @Test
    fun `removes project modules based on cohort modules`() {
      insertCohort()
      insertModule()
      insertCohortModule()
      insertProject()
      insertProjectModule()

      val expected = dslContext.fetch(PROJECT_MODULES)

      val cohortId = insertCohort()
      val projectId = insertProject(cohortId = cohortId)
      insertModule()
      insertCohortModule(
          startDate = LocalDate.of(2026, 1, 1),
          endDate = LocalDate.of(2026, 1, 2),
          title = "Module 1",
      )
      insertProjectModule(
          startDate = LocalDate.of(2026, 1, 3),
          endDate = LocalDate.of(2026, 1, 4),
          title = "Module 1 (Project)",
      )
      insertModule()
      insertCohortModule(
          startDate = LocalDate.of(2026, 1, 5),
          endDate = LocalDate.of(2026, 1, 6),
          title = "Module 2",
      )
      insertProjectModule(
          startDate = LocalDate.of(2026, 1, 5),
          endDate = LocalDate.of(2026, 1, 6),
          title = "Module 2",
      )

      service.on(CohortProjectRemovedEvent(cohortId, projectId, user.userId))

      assertTableEquals(expected)
    }
  }

  @Nested
  inner class OnCohortPhaseUpdatedEvent {
    @Test
    fun `updates phase for all projects in cohort`() {
      val cohortId = insertCohort(phase = CohortPhase.Phase0DueDiligence)
      val projectId1 = insertProject(cohortId = cohortId, phase = CohortPhase.Phase0DueDiligence)
      val projectId2 = insertProject(cohortId = cohortId, phase = CohortPhase.Phase0DueDiligence)

      insertCohort(phase = CohortPhase.Phase1FeasibilityStudy)
      insertProject(cohortId = inserted.cohortId, phase = CohortPhase.Phase1FeasibilityStudy)
      insertProject()

      val expected =
          dslContext.fetch(PROJECTS).onEach { record ->
            if (record.id == projectId1 || record.id == projectId2) {
              record.phaseId = CohortPhase.Phase3ImplementAndMonitor
            }
          }

      service.on(CohortPhaseUpdatedEvent(cohortId, CohortPhase.Phase3ImplementAndMonitor))

      assertTableEquals(expected)
    }
  }
}
