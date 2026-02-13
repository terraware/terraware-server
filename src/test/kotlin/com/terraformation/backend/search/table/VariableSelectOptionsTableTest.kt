package com.terraformation.backend.search.table

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VariableSelectOptionsTableTest : DatabaseTest(), RunsAsUser {
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
    insertProject(phase = CohortPhase.Phase1FeasibilityStudy)

    every { user.canReadAllAcceleratorDetails() } returns true
    every { user.organizationRoles } returns mapOf(inserted.organizationId to Role.Admin)
  }

  @Test
  fun `returns variable value options, ignoring old versions`() {
    val singleStableId = "123"
    val multiStableId = "456"
    val oldSingleVariableId = insertVariable(stableId = singleStableId, type = VariableType.Select)
    insertSelectVariable(oldSingleVariableId, isMultiple = false)
    val newSingleVariableId = insertVariable(stableId = singleStableId, type = VariableType.Select)
    insertSelectVariable(newSingleVariableId, isMultiple = false)
    val oldMultiVariableId = insertVariable(stableId = multiStableId, type = VariableType.Select)
    insertSelectVariable(oldMultiVariableId, isMultiple = true)
    val newMultiVariableId = insertVariable(stableId = multiStableId, type = VariableType.Select)
    insertSelectVariable(newMultiVariableId, isMultiple = true)

    insertSelectOption(variableId = oldSingleVariableId, name = "Old option 1")
    val oldOption2 = insertSelectOption(variableId = oldSingleVariableId, name = "Old option 2")
    val newOption1 = insertSelectOption(variableId = newSingleVariableId, name = "New option 1")
    insertSelectOption(variableId = newSingleVariableId, name = "New option 2")
    val oldMultiOption1 =
        insertSelectOption(variableId = oldMultiVariableId, name = "Old multi option 1")
    val oldMultiOption2 =
        insertSelectOption(variableId = oldMultiVariableId, name = "Old multi option 2")
    val newMultiOption1 =
        insertSelectOption(variableId = newMultiVariableId, name = "New multi option 1")
    val newMultiOption2 =
        insertSelectOption(variableId = newMultiVariableId, name = "New multi option 2")

    insertSelectValue(variableId = oldSingleVariableId, optionIds = setOf(oldOption2))
    insertSelectValue(variableId = newSingleVariableId, optionIds = setOf(newOption1))
    insertSelectValue(
        variableId = oldMultiVariableId,
        optionIds = setOf(oldMultiOption1, oldMultiOption2),
    )
    insertSelectValue(
        variableId = newMultiVariableId,
        optionIds = setOf(newMultiOption1, newMultiOption2),
    )

    val prefix = SearchFieldPrefix(searchTables.projectVariables)
    val fields =
        listOf(
                "variableId",
                "variableType",
                "isMultiSelect",
                "values.options.id",
                "values.options.name",
                "values.options.position",
            )
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "variableId" to "$newSingleVariableId",
                    "variableType" to "Select",
                    "isMultiSelect" to "false",
                    "values" to
                        listOf(
                            mapOf(
                                "options" to
                                    listOf(
                                        mapOf(
                                            "id" to "$newOption1",
                                            "name" to "New option 1",
                                            "position" to "2",
                                        )
                                    ),
                            )
                        ),
                ),
                mapOf(
                    "variableId" to "$newMultiVariableId",
                    "variableType" to "Select",
                    "isMultiSelect" to "true",
                    "values" to
                        listOf(
                            mapOf(
                                "options" to
                                    listOf(
                                        mapOf(
                                            "id" to "$newMultiOption1",
                                            "name" to "New multi option 1",
                                            "position" to "6",
                                        ),
                                        mapOf(
                                            "id" to "$newMultiOption2",
                                            "name" to "New multi option 2",
                                            "position" to "7",
                                        ),
                                    ),
                            )
                        ),
                ),
            )
        )

    val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `does not return if user can't read variables`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    val variableId = insertVariable(type = VariableType.Select)
    insertSelectVariable(variableId, isMultiple = false)
    val optionId = insertSelectOption(variableId = variableId, name = "An option")
    insertSelectValue(variableId = variableId, optionIds = setOf(optionId))

    val expected = SearchResults(emptyList())

    val prefix1 = SearchFieldPrefix(searchTables.projectVariableValues)
    val fields1 = listOf("options.id", "options.name").map { prefix1.resolve(it) }
    val actual1 = searchService.search(prefix1, fields1, mapOf(prefix1 to NoConditionNode()))
    assertJsonEquals(expected, actual1)

    val prefix2 = SearchFieldPrefix(searchTables.variableSelectOptions)
    val fields2 = listOf("id", "name").map { prefix2.resolve(it) }
    val actual2 = searchService.search(prefix2, fields2, mapOf(prefix2 to NoConditionNode()))
    assertJsonEquals(expected, actual2)
  }
}
