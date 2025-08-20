package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.use
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class ProjectVariableSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(userId = inserted.userId, role = Role.Admin)
    insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)

    every { user.canReadAllAcceleratorDetails() } returns true
    every { user.organizationRoles } returns mapOf(inserted.organizationId to Role.Admin)
  }

  @Test
  fun `returns variables for project, ignoring old variables`() {
    val projectId = insertProject()

    val textStableId = "123"
    val numberStableId = "456"
    val dateStableId = "789"
    val linkStableId = "101"
    val oldVariableId = insertVariable(stableId = textStableId)
    val newVariableId = insertVariable(stableId = textStableId, replacesVariableId = oldVariableId)
    val otherVariableId = insertVariable(stableId = numberStableId, type = VariableType.Number)
    val dateVariableId = insertVariable(stableId = dateStableId, type = VariableType.Date)
    val oldLinkVariableId = insertVariable(stableId = linkStableId, type = VariableType.Link)
    val newLinkVariableId =
        insertVariable(
            stableId = linkStableId,
            type = VariableType.Link,
            replacesVariableId = oldLinkVariableId,
        )

    // Add a connection between the project and each variable
    insertValue(variableId = newVariableId, textValue = "NewVarOldVal")
    insertValue(variableId = otherVariableId, numberValue = BigDecimal("456.456"))
    insertValue(variableId = dateVariableId, dateValue = LocalDate.of(2024, 1, 2))
    insertLinkValue(variableId = newLinkVariableId, url = "https://www.newValue.com")

    val prefix = SearchFieldPrefix(searchTables.projectVariables)
    val fields =
        listOf(
                "projectId",
                "stableId",
                "variableId",
                "variableType",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to linkStableId,
                    "variableId" to "$newLinkVariableId",
                    "variableType" to "Link",
                ),
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to textStableId,
                    "variableId" to "$newVariableId",
                    "variableType" to "Text",
                ),
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to numberStableId,
                    "variableId" to "$otherVariableId",
                    "variableType" to "Number",
                ),
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to dateStableId,
                    "variableId" to "$dateVariableId",
                    "variableType" to "Date",
                ),
            ),
            cursor = null,
        )

    val actual =
        Locales.GIBBERISH.use {
          searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))
        }

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `can retrieve nested variables from projects`() {
    val projectId = insertProject()
    val variableId1 = insertVariable()
    val variableId2 = insertVariable()
    insertValue(variableId = variableId1)
    insertValue(variableId = variableId2)

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields = listOf("id", "variables.variableId").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$projectId",
                    "variables" to
                        listOf(
                            mapOf(
                                "variableId" to "$variableId1",
                            ),
                            mapOf(
                                "variableId" to "$variableId2",
                            ),
                        ),
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
  fun `returns only variables of projects visible to non-accelerator admin users`() {
    every { user.canReadAllAcceleratorDetails() } returns false

    val projectId1 = insertProject()
    val variableId1 = insertVariable()
    insertValue(variableId = variableId1, textValue = "Visible")

    val otherOrgId = insertOrganization()
    insertProject(organizationId = otherOrgId)
    val otherVariableId = insertVariable()
    insertValue(variableId = otherVariableId, textValue = "Not visible")

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields = listOf("id", "variables.variableId").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$projectId1",
                    "variables" to
                        listOf(
                            mapOf(
                                "variableId" to "$variableId1",
                            )
                        ),
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
  @Disabled("Waiting on SW-7192")
  fun `filters by stable id when prefix starts at project`() {
    val projectId1 = insertProject(name = "Project 1")
    val projectId2 = insertProject(name = "Project 2")

    val stableId1 = "123"
    val stableId2 = "456"
    val stableId3 = "789"
    val variableId1 = insertVariable(stableId = stableId1)
    val variableId2 = insertVariable(stableId = stableId2)
    val variableId3 = insertVariable(stableId = stableId3)

    val included1 =
        insertValue(variableId = variableId1, projectId = projectId1, textValue = "Included1")
    insertValue(variableId = variableId2, projectId = projectId1, textValue = "Excluded2")
    val included3 =
        insertValue(variableId = variableId3, projectId = projectId1, textValue = "Included3")
    insertValue(variableId = variableId1, projectId = projectId2, textValue = "ExcludedProject2")

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields =
        listOf(
                "id",
                "name",
                "variables.projectId",
                "variables.stableId",
                "variables.variableId",
                "variables.variableValueId",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$projectId1",
                    "name" to "Project 1",
                    "variables" to
                        mapOf(
                            "projectId" to "$projectId1",
                            "stableId" to stableId1,
                            "variableId" to "$variableId1",
                            "variableValueId" to "$included1",
                        ),
                ),
                mapOf(
                    "id" to "$projectId1",
                    "name" to "Project 1",
                    "variables" to
                        mapOf(
                            "projectId" to "$projectId1",
                            "stableId" to stableId3,
                            "variableId" to "$variableId3",
                            "variableValueId" to "$included3",
                        ),
                ),
            ),
            cursor = null,
        )

    val search =
        AndNode(
            listOf(
                FieldNode(prefix.resolve("id"), listOf(projectId1.toString())),
                FieldNode(prefix.resolve("variables.stableId"), listOf(stableId1, stableId3)),
            )
        )
    val actual =
        Locales.GIBBERISH.use { searchService.search(prefix, fields, mapOf(prefix to search)) }

    assertJsonEquals(expected, actual)
  }
}
