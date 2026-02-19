package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.use
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModuleSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(
        userId = inserted.userId,
        organizationId = inserted.organizationId,
        role = Role.Admin,
    )

    every { user.canReadAllAcceleratorDetails() } returns true
    every { user.organizationRoles } returns mapOf(inserted.organizationId to Role.Admin)
  }

  @Test
  fun `searches for all modules`() {
    val prefix = SearchFieldPrefix(searchTables.modules)
    val fields =
        listOf(
                "id",
                "additionalResources",
                "liveSessionDescription",
                "name",
                "oneOnOneSessionDescription",
                "overview",
                "preparationMaterials",
                "phase",
                "workshopDescription",
            )
            .map { prefix.resolve(it) }

    val moduleId =
        insertModule(
            additionalResources = "<b> Additional Resources </b>",
            liveSessionDescription = "Live session lectures",
            name = "Test Module",
            oneOnOneSessionDescription = "1:1 meetings",
            overview = "<h> Overview </h>",
            preparationMaterials = "<i> Preps </i>",
            phase = CohortPhase.Phase1FeasibilityStudy,
            workshopDescription = "Workshop ideas",
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$moduleId",
                    "additionalResources" to "<b> Additional Resources </b>",
                    "liveSessionDescription" to "Live session lectures",
                    "name" to "Test Module",
                    "oneOnOneSessionDescription" to "1:1 meetings",
                    "overview" to "<h> Overview </h>",
                    "preparationMaterials" to "<i> Preps </i>",
                    "phase" to CohortPhase.Phase1FeasibilityStudy.getDisplayName(Locales.GIBBERISH),
                    "workshopDescription" to "Workshop ideas",
                )
            )
        )

    val actual =
        Locales.GIBBERISH.use {
          searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))
        }
    assertJsonEquals(expected, actual)
  }

  @Test
  fun `searches for project modules and events by modules`() {
    val prefix = SearchFieldPrefix(searchTables.modules)
    val fields =
        listOf(
                "id",
                "projectModules.title",
                "projectModules.startDate",
                "projectModules.endDate",
                "projectModules.project.id",
                "events.id",
            )
            .map { prefix.resolve(it) }

    val module1 = insertModule()
    val module2 = insertModule()
    val module3 = insertModule()

    val project1 = insertProject(phase = CohortPhase.Phase0DueDiligence)
    val project2 = insertProject(phase = CohortPhase.Phase0DueDiligence)

    insertProjectModule(
        project1,
        module1,
        "Week 1",
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 1, 2),
    )
    insertProjectModule(
        project1,
        module2,
        "Week 2",
        LocalDate.of(2024, 1, 3),
        LocalDate.of(2024, 1, 4),
    )

    insertProjectModule(
        project2,
        module2,
        "Module 1",
        LocalDate.of(2024, 2, 1),
        LocalDate.of(2024, 2, 2),
    )
    insertProjectModule(
        project2,
        module3,
        "Module 2",
        LocalDate.of(2024, 2, 3),
        LocalDate.of(2024, 2, 4),
    )

    val event1A = insertEvent(moduleId = module1)
    val event1B = insertEvent(moduleId = module1)

    val event3A = insertEvent(moduleId = module3)
    val event3B = insertEvent(moduleId = module3)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$module1",
                    "projectModules" to
                        listOf(
                            mapOf(
                                "title" to "Week 1",
                                "startDate" to LocalDate.of(2024, 1, 1).toString(),
                                "endDate" to LocalDate.of(2024, 1, 2).toString(),
                                "project" to mapOf("id" to "$project1"),
                            ),
                        ),
                    "events" to
                        listOf(
                            mapOf("id" to "$event1A"),
                            mapOf("id" to "$event1B"),
                        ),
                ),
                mapOf(
                    "id" to "$module2",
                    "projectModules" to
                        listOf(
                            mapOf(
                                "title" to "Week 2",
                                "startDate" to LocalDate.of(2024, 1, 3).toString(),
                                "endDate" to LocalDate.of(2024, 1, 4).toString(),
                                "project" to mapOf("id" to "$project1"),
                            ),
                            mapOf(
                                "title" to "Module 1",
                                "startDate" to LocalDate.of(2024, 2, 1).toString(),
                                "endDate" to LocalDate.of(2024, 2, 2).toString(),
                                "project" to mapOf("id" to "$project2"),
                            ),
                        ),
                ),
                mapOf(
                    "id" to "$module3",
                    "projectModules" to
                        listOf(
                            mapOf(
                                "title" to "Module 2",
                                "startDate" to LocalDate.of(2024, 2, 3).toString(),
                                "endDate" to LocalDate.of(2024, 2, 4).toString(),
                                "project" to mapOf("id" to "$project2"),
                            ),
                        ),
                    "events" to
                        listOf(
                            mapOf("id" to "$event3A"),
                            mapOf("id" to "$event3B"),
                        ),
                ),
            ),
            null,
        )

    val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))
    assertJsonEquals(expected, actual)
  }

  @Test
  fun `returns only modules assigned to projects visible to non accelerator admin users`() {
    every { user.canReadAllAcceleratorDetails() } returns false

    val prefix = SearchFieldPrefix(searchTables.modules)
    val fields = listOf("id", "cohortModules.cohort.id").map { prefix.resolve(it) }

    val module1 = insertModule()
    val module2 = insertModule()
    val invisibleModule = insertModule()

    val userProject = insertProject(phase = CohortPhase.Phase0DueDiligence)

    val otherUser = insertUser()
    val otherOrganization = insertOrganization()
    insertOrganizationUser(otherUser, otherOrganization)
    val otherProject = insertProject(phase = CohortPhase.Phase0DueDiligence)

    insertProjectModule(userProject, module1)
    insertProjectModule(userProject, module2)
    insertProjectModule(otherProject, module2)
    insertProjectModule(otherProject, invisibleModule)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$module1",
                    "projectModules" to listOf(mapOf("project" to mapOf("id" to "$userProject"))),
                ),
                mapOf(
                    "id" to "$module2",
                    "projectModules" to listOf(mapOf("project" to mapOf("id" to "$userProject"))),
                ),
            ),
            null,
        )

    val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))
    assertJsonEquals(expected, actual)
  }
}
