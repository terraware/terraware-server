package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.ModulesUploadedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.records.ModulesRecord
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ModulesImporterTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val importer: ModulesImporter by lazy {
    ModulesImporter(
        clock,
        dslContext,
        eventPublisher,
    )
  }

  private val header =
      "Name,ID,Phase,Module Overview,Preparation Materials,Additional Resources,Live Session Information,1:1 Information,Workshop Information,Recorded Session Information"

  @BeforeEach
  fun setUp() {
    every { user.canManageModules() } returns true
  }

  @Nested
  inner class ImportModules {
    @Test
    fun `upsert modules`() {
      val existingModuleId =
          insertModule(
              overview = "Existing Overview",
              additionalResources = "Existing Additional Resources",
          )

      val newModuleId1 = getUnusedModuleId()
      val newModuleId2 = getUnusedModuleId()

      val existingModuleCsv =
          "Existing Module,$existingModuleId,${CohortPhase.Application.jsonValue},New Overview,,,,,New Workshop,"

      val module1Csv =
          "Module 1,$newModuleId1,${CohortPhase.Phase1FeasibilityStudy.jsonValue},Phase 1 Overview,Phase 1 Prep,Phase 1 Add,Phase 1 Live,Phase 1 1:1,Phase 1 Workshop,"

      val module2Csv =
          "Module 2,$newModuleId2,${CohortPhase.Phase2PlanAndScale.id},Phase 2 Overview,Phase 2 Prep,Phase 2 Add,Phase 2 Live,Phase 2 1:1,Phase 2 Workshop,Phase 2 Recorded"

      clock.instant = Instant.ofEpochSecond(3000)

      val csv = header + "\n" + module1Csv + "\n" + module2Csv + "\n" + existingModuleCsv
      importer.importModules(csv.byteInputStream())

      assertTableEquals(
          listOf(
              ModulesRecord(
                  id = existingModuleId,
                  name = "Existing Module",
                  phaseId = CohortPhase.Application,
                  overview = "New Overview",
                  preparationMaterials = null,
                  additionalResources = null,
                  liveSessionDescription = null,
                  oneOnOneSessionDescription = null,
                  workshopDescription = "New Workshop",
                  position = 4,
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ModulesRecord(
                  id = newModuleId1,
                  name = "Module 1",
                  phaseId = CohortPhase.Phase1FeasibilityStudy,
                  overview = "Phase 1 Overview",
                  preparationMaterials = "Phase 1 Prep",
                  additionalResources = "Phase 1 Add",
                  liveSessionDescription = "Phase 1 Live",
                  oneOnOneSessionDescription = "Phase 1 1:1",
                  workshopDescription = "Phase 1 Workshop",
                  position = 2,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ModulesRecord(
                  id = newModuleId2,
                  name = "Module 2",
                  phaseId = CohortPhase.Phase2PlanAndScale,
                  overview = "Phase 2 Overview",
                  preparationMaterials = "Phase 2 Prep",
                  additionalResources = "Phase 2 Add",
                  liveSessionDescription = "Phase 2 Live",
                  oneOnOneSessionDescription = "Phase 2 1:1",
                  recordedSessionDescription = "Phase 2 Recorded",
                  workshopDescription = "Phase 2 Workshop",
                  position = 3,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
          )
      )

      eventPublisher.assertEventPublished { event -> event is ModulesUploadedEvent }
    }
  }

  /**
   * Returns a module ID that doesn't exist in the database and won't conflict with IDs from other
   * tests running in parallel.
   */
  private fun getUnusedModuleId(): ModuleId {
    val moduleId = insertModule()
    dslContext.deleteFrom(MODULES).where(MODULES.ID.eq(moduleId)).execute()
    return moduleId
  }
}
