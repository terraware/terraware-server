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

class ProjectVariableValueSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables = SearchTables(clock)

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(
        userId = inserted.userId, organizationId = inserted.organizationId, role = Role.Admin)
    insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)

    every { user.canReadAllAcceleratorDetails() } returns true
    every { user.organizationRoles } returns mapOf(inserted.organizationId to Role.Admin)
  }

  @Test
  fun `returns variable values for project, ignoring old values`() {
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
    val newLinkVariableId = insertVariable(stableId = linkStableId, type = VariableType.Link)

    insertValue(variableId = oldVariableId, projectId = projectId, textValue = "OldVarOldVal")
    insertValue(variableId = oldVariableId, projectId = projectId, textValue = "OldVarNewVal")
    insertValue(variableId = newVariableId, projectId = projectId, textValue = "NewVarOldVal")
    val newVarNewValueId =
        insertValue(variableId = newVariableId, projectId = projectId, textValue = "NewVarNewVal")
    val otherValueId =
        insertValue(
            variableId = otherVariableId,
            projectId = projectId,
            numberValue = BigDecimal("456.456"))
    val dateValueId =
        insertValue(
            variableId = dateVariableId,
            projectId = projectId,
            dateValue = LocalDate.of(2024, 1, 2))
    insertLinkValue(variableId = oldLinkVariableId, url = "https://www.oldVariable.com")
    insertLinkValue(variableId = newLinkVariableId, url = "https://www.oldValue.com")
    val newLinkValueId =
        insertLinkValue(variableId = newLinkVariableId, url = "https://www.newValue.com")

    val prefix = SearchFieldPrefix(searchTables.projectVariableValues)
    val fields =
        listOf(
                "projectId",
                "stableId",
                "variableId",
                "variableValueId",
                "variableType",
                "textValue",
                "numberValue",
                "linkValue",
                "dateValue")
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to linkStableId,
                    "variableId" to "$newLinkVariableId",
                    "variableValueId" to "$newLinkValueId",
                    "variableType" to "Link",
                    "linkValue" to "https://www.newValue.com",
                ),
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to textStableId,
                    "variableId" to "$newVariableId",
                    "variableValueId" to "$newVarNewValueId",
                    "variableType" to "Text",
                    "textValue" to "NewVarNewVal",
                ),
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to numberStableId,
                    "variableId" to "$otherVariableId",
                    "variableValueId" to "$otherValueId",
                    "variableType" to "Number",
                    "numberValue" to "456,456",
                ),
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to dateStableId,
                    "variableId" to "$dateVariableId",
                    "variableValueId" to "$dateValueId",
                    "variableType" to "Date",
                    "dateValue" to "2024-01-02",
                ),
            ),
            cursor = null)

    val actual = Locales.GIBBERISH.use { searchService.search(prefix, fields, NoConditionNode()) }

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `can retrieve nested variableValues from projects`() {
    val projectId = insertProject()
    val variableId1 = insertVariable()
    val variableId2 = insertVariable()
    val valueId1 = insertValue(variableId = variableId1, projectId = projectId)
    val valueId2 = insertValue(variableId = variableId2, projectId = projectId)

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields =
        listOf("id", "variableValues.variableId", "variableValues.variableValueId").map {
          prefix.resolve(it)
        }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$projectId",
                    "variableValues" to
                        listOf(
                            mapOf(
                                "variableId" to "$variableId1",
                                "variableValueId" to "$valueId1",
                            ),
                            mapOf(
                                "variableId" to "$variableId2",
                                "variableValueId" to "$valueId2",
                            ),
                        ))))

    val actual = Locales.GIBBERISH.use { searchService.search(prefix, fields, NoConditionNode()) }

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `returns only variables of projects visible to non-accelerator admin users`() {
    every { user.canReadAllAcceleratorDetails() } returns false

    val projectId1 = insertProject()
    val variableId1 = insertVariable()
    val valueId1 =
        insertValue(variableId = variableId1, projectId = projectId1, textValue = "Visible")

    val otherOrgId = insertOrganization()
    val otherProject = insertProject(organizationId = otherOrgId)
    val otherVariableId = insertVariable()
    insertValue(variableId = otherVariableId, projectId = otherProject, textValue = "Not visible")

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields =
        listOf("id", "variableValues.variableId", "variableValues.variableValueId").map {
          prefix.resolve(it)
        }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$projectId1",
                    "variableValues" to
                        listOf(
                            mapOf(
                                "variableId" to "$variableId1",
                                "variableValueId" to "$valueId1",
                            )))))

    val actual = Locales.GIBBERISH.use { searchService.search(prefix, fields, NoConditionNode()) }

    assertJsonEquals(expected, actual)
  }

  @Test
  @Disabled(
      "Filtering by stable id in a sublist does not work in the search api. Unsure yet if this will be added or not.")
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
                "variableValues.projectId",
                "variableValues.stableId",
                "variableValues.variableId",
                "variableValues.variableValueId")
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$projectId1",
                    "name" to "Project 1",
                    "variableValues" to
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
                    "variableValues" to
                        mapOf(
                            "projectId" to "$projectId1",
                            "stableId" to stableId3,
                            "variableId" to "$variableId3",
                            "variableValueId" to "$included3",
                        ),
                ),
            ),
            cursor = null)

    val search =
        AndNode(
            listOf(
                FieldNode(prefix.resolve("id"), listOf(projectId1.toString())),
                FieldNode(prefix.resolve("variableValues.stableId"), listOf(stableId1, stableId3)),
            ))
    val actual = Locales.GIBBERISH.use { searchService.search(prefix, fields, search) }

    assertJsonEquals(expected, actual)
  }
}
