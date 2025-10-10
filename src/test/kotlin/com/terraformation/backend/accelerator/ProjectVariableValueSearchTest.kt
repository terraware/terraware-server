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
import java.time.LocalDate
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
        userId = inserted.userId,
        organizationId = inserted.organizationId,
        role = Role.Admin,
    )
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
    val listStableId = "112"
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
    val oldListVariableId = insertVariable(stableId = listStableId, isList = true)
    val newListVariableId =
        insertVariable(
            stableId = listStableId,
            isList = true,
            replacesVariableId = oldListVariableId,
        )

    insertValue(variableId = oldVariableId, textValue = "OldVarOldVal")
    insertValue(variableId = oldVariableId, textValue = "OldVarNewVal")
    insertValue(variableId = newVariableId, textValue = "NewVarOldVal")
    val newVarNewValueId = insertValue(variableId = newVariableId, textValue = "NewVarNewVal")
    val otherValueId =
        insertValue(variableId = otherVariableId, numberValue = BigDecimal("456.456"))

    val dateValueId = insertValue(variableId = dateVariableId, dateValue = LocalDate.of(2024, 1, 2))

    insertLinkValue(variableId = oldLinkVariableId, url = "https://www.oldVariable.com")
    insertLinkValue(variableId = newLinkVariableId, url = "https://www.oldValue.com")
    val newLinkValueId =
        insertLinkValue(variableId = newLinkVariableId, url = "https://www.newValue.com")

    insertValue(variableId = oldListVariableId, textValue = "OldListValue1", listPosition = 0)
    insertValue(variableId = oldListVariableId, textValue = "OldListValue2", listPosition = 1)
    val listVal1 =
        insertValue(variableId = newListVariableId, textValue = "NewListValue1", listPosition = 0)
    val listVal2 =
        insertValue(variableId = newListVariableId, textValue = "NewListValue2", listPosition = 1)

    val prefix = SearchFieldPrefix(searchTables.projectVariables)
    val fields =
        listOf(
                "projectId",
                "stableId",
                "variableId",
                "variableType",
                "isList",
                "values.variableValueId",
                "values.textValue",
                "values.numberValue",
                "values.dateValue",
                "values.linkUrl",
                "values.listPosition",
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
                    "isList" to "false",
                    "values" to
                        listOf(
                            mapOf(
                                "listPosition" to "0",
                                "variableValueId" to "$newLinkValueId",
                                "linkUrl" to "https://www.newValue.com",
                            )
                        ),
                ),
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to listStableId,
                    "variableId" to "$newListVariableId",
                    "variableType" to "Text",
                    "isList" to "true",
                    "values" to
                        listOf(
                            mapOf(
                                "listPosition" to "0",
                                "variableValueId" to "$listVal1",
                                "textValue" to "NewListValue1",
                            ),
                            mapOf(
                                "listPosition" to "1",
                                "variableValueId" to "$listVal2",
                                "textValue" to "NewListValue2",
                            ),
                        ),
                ),
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to textStableId,
                    "variableId" to "$newVariableId",
                    "variableType" to "Text",
                    "isList" to "false",
                    "values" to
                        listOf(
                            mapOf(
                                "listPosition" to "0",
                                "variableValueId" to "$newVarNewValueId",
                                "textValue" to "NewVarNewVal",
                            )
                        ),
                ),
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to numberStableId,
                    "variableId" to "$otherVariableId",
                    "variableType" to "Number",
                    "isList" to "false",
                    "values" to
                        listOf(
                            mapOf(
                                "listPosition" to "0",
                                "variableValueId" to "$otherValueId",
                                "numberValue" to "456.456",
                            )
                        ),
                ),
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to dateStableId,
                    "variableId" to "$dateVariableId",
                    "variableType" to "Date",
                    "isList" to "false",
                    "values" to
                        listOf(
                            mapOf(
                                "listPosition" to "0",
                                "variableValueId" to "$dateValueId",
                                "dateValue" to "2024-01-02",
                            )
                        ),
                ),
            ),
            cursor = null,
        )

    val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `can retrieve nested variableValues from projects`() {
    val projectId = insertProject()
    val variableId1 = insertVariable()
    val variableId2 = insertVariable()
    val valueId1 = insertValue(variableId = variableId1)
    val valueId2 = insertValue(variableId = variableId2)

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields =
        listOf("id", "variables.variableId", "variables.values.variableValueId").map {
          prefix.resolve(it)
        }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$projectId",
                    "variables" to
                        listOf(
                            mapOf(
                                "variableId" to "$variableId1",
                                "values" to listOf(mapOf("variableValueId" to "$valueId1")),
                            ),
                            mapOf(
                                "variableId" to "$variableId2",
                                "values" to listOf(mapOf("variableValueId" to "$valueId2")),
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
  fun `old variable with value doesn't show up when new version does not have value`() {
    val projectId = insertProject()
    val stableId = "123"
    val oldVariableId = insertVariable(stableId = stableId)
    insertVariable(stableId = stableId, replacesVariableId = oldVariableId)
    insertValue(variableId = oldVariableId, textValue = "OldStuff")

    val referenceStableId = "456"
    val referenceVariableId = insertVariable(stableId = referenceStableId)
    insertValue(variableId = referenceVariableId)

    val prefix = SearchFieldPrefix(searchTables.projectVariables)
    val fields = listOf("projectId", "stableId").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "projectId" to "$projectId",
                    "stableId" to referenceStableId,
                )
            ),
            cursor = null,
        )
    val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `deleted values exclude the variables fully`() {
    insertProject()
    val variableId1 = insertVariable()
    val variableId2 = insertVariable()
    insertValue(variableId = variableId1, textValue = "Value1")
    insertValue(variableId = variableId1, textValue = "Value2", isDeleted = true)
    insertValue(variableId = variableId2, textValue = "OtherValue")

    val prefix = SearchFieldPrefix(searchTables.projectVariableValues)
    val fields = listOf("variableId", "textValue").map { prefix.resolve(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "variableId" to "$variableId2",
                    "textValue" to "OtherValue",
                ),
            )
        )
    val actual = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `returns only variables of projects visible to non-accelerator admin users`() {
    every { user.canReadAllAcceleratorDetails() } returns false

    val projectId1 = insertProject()
    val variableId1 = insertVariable()
    val valueId1 = insertValue(variableId = variableId1, textValue = "Visible")

    val otherOrgId = insertOrganization()
    insertProject(organizationId = otherOrgId)
    val otherVariableId = insertVariable()
    insertValue(variableId = otherVariableId, textValue = "Not visible")

    val prefix = SearchFieldPrefix(searchTables.projects)
    val fields =
        listOf("id", "variables.variableId", "variables.values.variableValueId").map {
          prefix.resolve(it)
        }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$projectId1",
                    "variables" to
                        listOf(
                            mapOf(
                                "variableId" to "$variableId1",
                                "values" to listOf(mapOf("variableValueId" to "$valueId1")),
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
}
