package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException

class ModuleStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: ModuleStore by lazy { ModuleStore(dslContext) }
  private lateinit var cohortId: CohortId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    cohortId = insertCohort()
    insertParticipant(cohortId = inserted.cohortId)
    projectId = insertProject(participantId = inserted.participantId)

    every { user.canManageModules() } returns true
    every { user.canReadModule(any()) } returns true
  }

  @Nested
  inner class ModulesTableConstraints {
    @Test
    fun `throws exception if cohort module end date is before start date`() {
      val moduleId = insertModule()
      assertThrows<DataIntegrityViolationException> {
        insertCohortModule(
            cohortId,
            moduleId,
            startDate = LocalDate.of(2024, 1, 31),
            endDate = LocalDate.of(2024, 1, 30),
        )
      }
    }

    @Test
    fun `throws exception if event end time is before start time`() {
      insertModule()

      assertThrows<DataIntegrityViolationException> {
        insertEvent(startTime = Instant.ofEpochSecond(500), endTime = Instant.ofEpochSecond(400))
      }
    }
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `returns one module with all cohorts`() {
      val moduleId =
          insertModule(
              additionalResources = "<b> Additional Resources </b>",
              liveSessionDescription = "Live session lectures",
              name = "TestModule",
              oneOnOneSessionDescription = "1:1 meetings",
              overview = "<h> Overview </h>",
              preparationMaterials = "<i> Preps </i>",
              phase = CohortPhase.Phase1FeasibilityStudy,
              workshopDescription = "Workshop ideas",
          )

      assertEquals(
          ModuleModel(
              id = moduleId,
              name = "TestModule",
              phase = CohortPhase.Phase1FeasibilityStudy,
              position = 1,
              additionalResources = "<b> Additional Resources </b>",
              eventDescriptions =
                  mapOf(
                      EventType.LiveSession to "Live session lectures",
                      EventType.OneOnOneSession to "1:1 meetings",
                      EventType.Workshop to "Workshop ideas",
                  ),
              overview = "<h> Overview </h>",
              preparationMaterials = "<i> Preps </i>",
          ),
          store.fetchOneById(moduleId),
      )
    }

    @Test
    fun `throws exception if no permission to read module`() {
      val moduleId = insertModule()
      every { user.canReadModule(any()) } returns false
      assertThrows<ModuleNotFoundException> { store.fetchOneById(moduleId) }
    }

    @Test
    fun `throws exception if no module found`() {
      assertThrows<ModuleNotFoundException> { store.fetchOneById(ModuleId(-1)) }
    }
  }

  @Nested
  inner class FetchAllModules {
    @Test
    fun `returns empty list of module if no module added`() {
      assertEquals(emptyList<ModuleModel>(), store.fetchAllModules())
    }

    @Test
    fun `returns empty list of module if no permission to read any module`() {
      every { user.canReadModule(any()) } returns false
      assertEquals(emptyList<ModuleModel>(), store.fetchAllModules())
    }

    @Test
    fun `returns list of visible modules with details`() {
      val moduleId =
          insertModule(
              additionalResources = "<b> Additional Resources </b>",
              liveSessionDescription = "Live session lectures",
              name = "TestModule",
              oneOnOneSessionDescription = "1:1 meetings",
              overview = "<h> Overview </h>",
              preparationMaterials = "<i> Preps </i>",
              phase = CohortPhase.Phase1FeasibilityStudy,
              workshopDescription = "Workshop ideas",
          )

      val invisibleModuleId =
          insertModule(
              additionalResources = "<b> Invisible Additional Resources </b>",
              liveSessionDescription = "Invisible Live session lectures",
              name = "Invisible Module",
          )

      every { user.canReadModule(invisibleModuleId) } returns false

      assertEquals(
          listOf(
              ModuleModel(
                  id = moduleId,
                  name = "TestModule",
                  phase = CohortPhase.Phase1FeasibilityStudy,
                  position = 1,
                  additionalResources = "<b> Additional Resources </b>",
                  eventDescriptions =
                      mapOf(
                          EventType.LiveSession to "Live session lectures",
                          EventType.OneOnOneSession to "1:1 meetings",
                          EventType.Workshop to "Workshop ideas",
                      ),
                  overview = "<h> Overview </h>",
                  preparationMaterials = "<i> Preps </i>",
              )
          ),
          store.fetchAllModules(),
      )
    }

    @Test
    fun `returns all modules`() {
      val moduleIds = (1..10).map { insertModule() }

      val fetchAllResult = store.fetchAllModules()
      assertEquals(moduleIds, fetchAllResult.map { it.id })
    }
  }

  @Nested
  inner class FetchCohortPhase {
    @Test
    fun `returns module cohort phase`() {
      val moduleId = insertModule(phase = CohortPhase.Phase1FeasibilityStudy)
      assertEquals(CohortPhase.Phase1FeasibilityStudy, store.fetchCohortPhase(moduleId))
    }

    @Test
    fun `throws exception if no permission to read module`() {
      val moduleId = insertModule()
      every { user.canReadModule(any()) } returns false
      assertThrows<ModuleNotFoundException> { store.fetchCohortPhase(moduleId) }
    }

    @Test
    fun `throws exception if no module found`() {
      assertThrows<ModuleNotFoundException> { store.fetchCohortPhase(ModuleId(-1)) }
    }
  }
}
