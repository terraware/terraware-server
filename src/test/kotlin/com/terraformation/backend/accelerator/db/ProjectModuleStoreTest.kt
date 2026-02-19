package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.tables.records.ProjectModulesRecord
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_MODULES
import com.terraformation.backend.db.default_schema.GlobalRole
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ProjectModuleStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: ProjectModuleStore by lazy { ProjectModuleStore(dslContext) }

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
      insertOrganization()
      insertProject(phase = CohortPhase.Phase1FeasibilityStudy)

      deleteUserGlobalRole(role = GlobalRole.ReadOnly)

      assertThrows<ProjectNotFoundException> { store.fetch(projectId = inserted.projectId) }
      assertThrows<AccessDeniedException> { store.fetch() }
    }

    @Test
    fun `filters by IDs, ordered by project ID, start date, end date, position`() {
      insertOrganization()

      val projectA = insertProject(phase = CohortPhase.Phase0DueDiligence)
      val projectB = insertProject(phase = CohortPhase.Phase0DueDiligence)

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

      insertProjectModule(projectA, module1, "Equal dates, later position", date2, date3)
      insertProjectModule(projectA, module2, "Equal dates, earlier position", date2, date3)
      insertProjectModule(projectA, module3, "Equal start date, later end date", date2, date4)
      insertProjectModule(projectA, module4, "Earliest Start Date", date1, date2)

      insertProjectModule(projectB, module1, "Different project module", date1, date4)

      val projectAModule1 =
          ModuleModel(
              id = module1,
              name = "Module 1",
              phase = CohortPhase.Phase1FeasibilityStudy,
              position = 3,
              projectId = projectA,
              title = "Equal dates, later position",
              startDate = date2,
              endDate = date3,
          )

      val projectAModule2 =
          ModuleModel(
              id = module2,
              name = "Module 2",
              phase = CohortPhase.Phase1FeasibilityStudy,
              position = 1,
              projectId = projectA,
              title = "Equal dates, earlier position",
              startDate = date2,
              endDate = date3,
          )

      val projectAModule3 =
          ModuleModel(
              id = module3,
              name = "Module 3",
              phase = CohortPhase.Phase1FeasibilityStudy,
              position = 2,
              projectId = projectA,
              title = "Equal start date, later end date",
              startDate = date2,
              endDate = date4,
          )

      val projectAModule4 =
          ModuleModel(
              id = module4,
              name = "Module 4",
              phase = CohortPhase.Phase1FeasibilityStudy,
              position = 10,
              projectId = projectA,
              title = "Earliest Start Date",
              startDate = date1,
              endDate = date2,
          )

      val projectBModule1 =
          projectAModule1.copy(
              projectId = projectB,
              title = "Different project module",
              startDate = date1,
              endDate = date4,
          )

      val expectedProjectModulesA =
          listOf(
              projectAModule4,
              projectAModule2,
              projectAModule1,
              projectAModule3,
          )

      assertEquals(
          expectedProjectModulesA,
          store.fetch(projectId = projectA),
          "Fetch by Project ID",
      )
      verifyNoPermissionInversions()

      assertEquals(
          listOf(projectAModule4),
          store.fetch(projectId = projectA, moduleId = module4),
          "Fetch by Project ID and Module ID",
      )
      verifyNoPermissionInversions()

      assertEquals(
          listOf(projectBModule1),
          store.fetch(projectId = projectB),
          "Fetch by a different project ID",
      )
      verifyNoPermissionInversions()

      assertEquals(
          listOf(
              projectAModule4,
              projectAModule2,
              projectAModule1,
              projectAModule3,
              projectBModule1,
          ),
          store.fetch(),
          "Fetch all modules",
      )
      verifyNoPermissionInversions()

      assertEquals(
          listOf(projectAModule1, projectBModule1),
          store.fetch(moduleId = module1),
          "Fetch one module",
      )
    }
  }

  @Nested
  inner class AssignProject {
    @Test
    fun `throws exception if no associated permissions`() {
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      insertProject(phase = CohortPhase.Phase0DueDiligence)
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
    fun `throws exception if project is not in a phase`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertProject()
      insertModule()

      assertThrows<ProjectNotInCohortPhaseException> {
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
    fun `assigns new module to project`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertModule()

      store.assign(
          inserted.projectId,
          inserted.moduleId,
          "Module title",
          LocalDate.of(2024, 1, 1),
          LocalDate.of(2024, 1, 3),
      )

      assertTableEquals(
          ProjectModulesRecord(
              projectId = inserted.projectId,
              moduleId = inserted.moduleId,
              title = "Module title",
              startDate = LocalDate.of(2024, 1, 1),
              endDate = LocalDate.of(2024, 1, 3),
          )
      )
    }

    @Test
    fun `updates existing project module`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertModule()

      insertProjectModule(
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

      assertTableEquals(
          ProjectModulesRecord(
              projectId = inserted.projectId,
              moduleId = inserted.moduleId,
              title = "New module title",
              startDate = LocalDate.of(2024, 2, 1),
              endDate = LocalDate.of(2024, 2, 3),
          )
      )
    }
  }

  @Nested
  inner class RemoveProject {
    @Test
    fun `throws exception if no associated permissions`() {
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertModule()
      insertProjectModule()

      assertThrows<AccessDeniedException> { //
        store.remove(inserted.projectId, inserted.moduleId)
      }
    }

    @Test
    fun `throws exception if project is not in a phase`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertProject()
      insertModule()

      assertThrows<ProjectNotInCohortPhaseException> {
        store.remove(inserted.projectId, inserted.moduleId)
      }
    }

    @Test
    fun `removes existing project module`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertProject(phase = CohortPhase.Phase0DueDiligence)
      insertModule()

      insertProjectModule(
          title = "Module Title",
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 1, 3),
      )

      store.remove(inserted.projectId, inserted.moduleId)

      assertTableEmpty(PROJECT_MODULES)
    }
  }
}
