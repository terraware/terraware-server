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
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.BeforeEach
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
    val oldVariableId = insertVariable(stableId = textStableId)
    val newVariableId = insertVariable(stableId = textStableId, replacesVariableId = oldVariableId)
    val otherVariableId = insertVariable(stableId = numberStableId, type = VariableType.Number)

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

    val prefix = SearchFieldPrefix(searchTables.projectVariableValues)
    val fields =
        listOf("projectId", "stableId", "variableId", "variableValueId", "textValue", "numberValue")
            .map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to textStableId,
                    "variableId" to "$newVariableId",
                    "variableValueId" to "$newVarNewValueId",
                    "textValue" to "NewVarNewVal",
                ),
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to numberStableId,
                    "variableId" to "$otherVariableId",
                    "variableValueId" to "$otherValueId",
                    "numberValue" to "456,456",
                )),
            cursor = null)

    val actual = Locales.GIBBERISH.use { searchService.search(prefix, fields, NoConditionNode()) }

    assertJsonEquals(expected, actual)
  }
}
