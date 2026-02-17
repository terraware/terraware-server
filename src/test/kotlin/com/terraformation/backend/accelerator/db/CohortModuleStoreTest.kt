package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.tables.pojos.CohortModulesRow
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.default_schema.GlobalRole
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class CohortModuleStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: CohortModuleStore by lazy { CohortModuleStore(dslContext) }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertUserGlobalRole(role = GlobalRole.ReadOnly)
  }

  @Nested
  inner class Fetch {
    @Test
    fun `returns empty list of module if no module added`() {
      assertEquals(emptyList<ModuleModel>(), store.fetch())
    }

    @Test
    fun `throws exceptions if no associated permissions`() {
      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertOrganization()
      insertProject(cohortId = inserted.cohortId)

      deleteUserGlobalRole(role = GlobalRole.ReadOnly)

      assertThrows<ProjectNotFoundException> { store.fetch(projectId = inserted.projectId) }
      assertThrows<AccessDeniedException> { store.fetch() }
      assertThrows<CohortNotFoundException> { store.fetch(cohortId = inserted.cohortId) }
    }

    @Test
    fun `filters by IDs, ordered by cohort ID, start date, end date, position`() {
      insertOrganization()

      val cohortA = insertCohort(phase = CohortPhase.Phase0DueDiligence)
      val cohortB = insertCohort(phase = CohortPhase.Phase0DueDiligence)
      val projectA = insertProject(cohortId = cohortA, phase = CohortPhase.Phase0DueDiligence)
      val projectB = insertProject(cohortId = cohortB, phase = CohortPhase.Phase0DueDiligence)

      val module1 =
          insertModule(name = "Module 1", position = 3, phase = CohortPhase.Phase1FeasibilityStudy)
      val module2 =
          insertModule(name = "Module 2", position = 1, phase = CohortPhase.Phase1FeasibilityStudy)
      val module3 =
          insertModule(name = "Module 3", position = 2, phase = CohortPhase.Phase1FeasibilityStudy)
      val module4 =
          insertModule(name = "Module 4", position = 10, phase = CohortPhase.Phase1FeasibilityStudy)
      insertModule(name = "Hidden Module")

      val date1 = LocalDate.of(2024, 1, 5)
      val date2 = date1.plusDays(1)
      val date3 = date2.plusDays(1)
      val date4 = date3.plusDays(1)

      insertCohortModule(cohortA, module1, "Equal dates, later position", date2, date3)
      insertCohortModule(cohortA, module2, "Equal dates, earlier position", date2, date3)
      insertCohortModule(cohortA, module3, "Equal start date, later end date", date2, date4)
      insertCohortModule(cohortA, module4, "Earliest Start Date", date1, date2)

      insertCohortModule(cohortB, module1, "Different cohort module", date1, date4)

      val cohortAModule1 =
          ModuleModel(
              id = module1,
              name = "Module 1",
              phase = CohortPhase.Phase1FeasibilityStudy,
              position = 3,
              cohortId = cohortA,
              title = "Equal dates, later position",
              startDate = date2,
              endDate = date3,
          )

      val cohortAModule2 =
          ModuleModel(
              id = module2,
              name = "Module 2",
              phase = CohortPhase.Phase1FeasibilityStudy,
              position = 1,
              cohortId = cohortA,
              title = "Equal dates, earlier position",
              startDate = date2,
              endDate = date3,
          )

      val cohortAModule3 =
          ModuleModel(
              id = module3,
              name = "Module 3",
              phase = CohortPhase.Phase1FeasibilityStudy,
              position = 2,
              cohortId = cohortA,
              title = "Equal start date, later end date",
              startDate = date2,
              endDate = date4,
          )

      val cohortAModule4 =
          ModuleModel(
              id = module4,
              name = "Module 4",
              phase = CohortPhase.Phase1FeasibilityStudy,
              position = 10,
              cohortId = cohortA,
              title = "Earliest Start Date",
              startDate = date1,
              endDate = date2,
          )

      val cohortBModule1 =
          cohortAModule1.copy(
              cohortId = cohortB,
              title = "Different cohort module",
              startDate = date1,
              endDate = date4,
          )

      val expectedCohortModulesA =
          listOf(
              cohortAModule4,
              cohortAModule2,
              cohortAModule1,
              cohortAModule3,
          )

      assertEquals(expectedCohortModulesA, store.fetch(cohortId = cohortA), "Fetch by Cohort ID")
      verifyNoPermissionInversions()

      assertEquals(expectedCohortModulesA, store.fetch(projectId = projectA), "Fetch by Project ID")
      verifyNoPermissionInversions()

      assertEquals(
          listOf(cohortAModule4),
          store.fetch(projectId = projectA, moduleId = module4),
          "Fetch by Project ID and Module ID",
      )
      verifyNoPermissionInversions()

      assertEquals(
          listOf(cohortBModule1),
          store.fetch(cohortId = cohortB),
          "Fetch by a different Cohort ID",
      )
      verifyNoPermissionInversions()

      assertEquals(
          listOf(cohortBModule1),
          store.fetch(projectId = projectB),
          "Fetch by a different project ID",
      )
      verifyNoPermissionInversions()

      assertEquals(
          listOf(cohortAModule4, cohortAModule2, cohortAModule1, cohortAModule3, cohortBModule1),
          store.fetch(),
          "Fetch all modules",
      )
      verifyNoPermissionInversions()

      assertEquals(
          listOf(cohortAModule1, cohortBModule1),
          store.fetch(moduleId = module1),
          "Fetch one module",
      )
    }
  }

  @Nested
  inner class AssignCohort {
    @Test
    fun `throws exceptions if no associated permissions`() {
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertModule()

      assertThrows<AccessDeniedException> {
        store.assign(
            inserted.cohortId,
            inserted.moduleId,
            "Module title",
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 3),
        )
      }
    }

    @Test
    fun `assigns new cohort module`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertModule()

      store.assign(
          inserted.cohortId,
          inserted.moduleId,
          "Module title",
          LocalDate.of(2024, 1, 1),
          LocalDate.of(2024, 1, 3),
      )

      assertEquals(
          listOf(
              CohortModulesRow(
                  cohortId = inserted.cohortId,
                  moduleId = inserted.moduleId,
                  title = "Module title",
                  startDate = LocalDate.of(2024, 1, 1),
                  endDate = LocalDate.of(2024, 1, 3),
              )
          ),
          cohortModulesDao.findAll(),
      )
    }

    @Test
    fun `updates existing cohort module`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertModule()

      insertCohortModule(
          cohortId = inserted.cohortId,
          moduleId = inserted.moduleId,
          title = "Old module title",
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 1, 3),
      )

      store.assign(
          inserted.cohortId,
          inserted.moduleId,
          "New module title",
          LocalDate.of(2024, 2, 1),
          LocalDate.of(2024, 2, 3),
      )

      assertEquals(
          listOf(
              CohortModulesRow(
                  cohortId = inserted.cohortId,
                  moduleId = inserted.moduleId,
                  title = "New module title",
                  startDate = LocalDate.of(2024, 2, 1),
                  endDate = LocalDate.of(2024, 2, 3),
              )
          ),
          cohortModulesDao.findAll(),
      )
    }
  }

  @Nested
  inner class AssignProject {
    @Test
    fun `throws exception if no associated permissions`() {
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertProject(cohortId = inserted.cohortId)
      insertModule()

      assertThrows<AccessDeniedException> {
        store.assign(
            inserted.projectId,
            inserted.moduleId,
            "Module title",
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 3),
        )
      }
    }

    @Test
    fun `throws exception if project is not in a cohort`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertProject()
      insertModule()

      assertThrows<ProjectNotInCohortException> {
        store.assign(
            inserted.projectId,
            inserted.moduleId,
            "Module title",
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 3),
        )
      }
    }

    @Test
    fun `assigns new module to project's cohort`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertProject(cohortId = inserted.cohortId)
      insertModule()

      store.assign(
          inserted.projectId,
          inserted.moduleId,
          "Module title",
          LocalDate.of(2024, 1, 1),
          LocalDate.of(2024, 1, 3),
      )

      assertEquals(
          listOf(
              CohortModulesRow(
                  cohortId = inserted.cohortId,
                  moduleId = inserted.moduleId,
                  title = "Module title",
                  startDate = LocalDate.of(2024, 1, 1),
                  endDate = LocalDate.of(2024, 1, 3),
              )
          ),
          cohortModulesDao.findAll(),
      )
    }

    @Test
    fun `updates existing cohort module for project's cohort`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertProject(cohortId = inserted.cohortId)
      insertModule()

      insertCohortModule(
          cohortId = inserted.cohortId,
          moduleId = inserted.moduleId,
          title = "Old module title",
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 1, 3),
      )

      store.assign(
          inserted.projectId,
          inserted.moduleId,
          "New module title",
          LocalDate.of(2024, 2, 1),
          LocalDate.of(2024, 2, 3),
      )

      assertEquals(
          listOf(
              CohortModulesRow(
                  cohortId = inserted.cohortId,
                  moduleId = inserted.moduleId,
                  title = "New module title",
                  startDate = LocalDate.of(2024, 2, 1),
                  endDate = LocalDate.of(2024, 2, 3),
              )
          ),
          cohortModulesDao.findAll(),
      )
    }
  }

  @Nested
  inner class RemoveCohort {
    @Test
    fun `throws exceptions if no associated permissions`() {
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertModule()

      assertThrows<AccessDeniedException> {
        store.remove(
            inserted.cohortId,
            inserted.moduleId,
        )
      }
    }

    @Test
    fun `removes existing cohort module`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertModule()

      insertCohortModule(
          cohortId = inserted.cohortId,
          moduleId = inserted.moduleId,
          title = "Module Title",
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 1, 3),
      )

      store.remove(inserted.cohortId, inserted.moduleId)

      assertTableEmpty(COHORT_MODULES)
    }
  }

  @Nested
  inner class RemoveProject {
    @Test
    fun `throws exception if no associated permissions`() {
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertProject(cohortId = inserted.cohortId)
      insertModule()

      assertThrows<AccessDeniedException> { //
        store.remove(inserted.projectId, inserted.moduleId)
      }
    }

    @Test
    fun `throws exception if project is not in a cohort`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertProject(cohortId = null)
      insertModule()

      assertThrows<ProjectNotInCohortException> {
        store.remove(inserted.projectId, inserted.moduleId)
      }
    }

    @Test
    fun `removes existing cohort module`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertCohort(phase = CohortPhase.Phase0DueDiligence)
      insertProject(cohortId = inserted.cohortId)
      insertModule()

      insertCohortModule(
          cohortId = inserted.cohortId,
          moduleId = inserted.moduleId,
          title = "Module Title",
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 1, 3),
      )

      store.remove(inserted.projectId, inserted.moduleId)

      assertTableEmpty(COHORT_MODULES)
    }
  }
}
