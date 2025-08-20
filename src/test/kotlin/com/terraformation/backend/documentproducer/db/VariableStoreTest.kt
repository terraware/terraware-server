package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_VARIABLES
import com.terraformation.backend.db.docprod.VariableTableStyle
import com.terraformation.backend.db.docprod.VariableTextType
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.documentproducer.model.BaseVariableProperties
import com.terraformation.backend.documentproducer.model.NumberVariable
import com.terraformation.backend.documentproducer.model.SectionVariable
import com.terraformation.backend.documentproducer.model.TableColumn
import com.terraformation.backend.documentproducer.model.TableVariable
import com.terraformation.backend.documentproducer.model.TextVariable
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.mockUser
import io.mockk.every
import java.math.BigDecimal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VariableStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: VariableStore by lazy {
    VariableStore(
        dslContext,
        variableNumbersDao,
        variablesDao,
        variableSectionDefaultValuesDao,
        variableSectionRecommendationsDao,
        variableSectionsDao,
        variableSelectsDao,
        variableSelectOptionsDao,
        variableTablesDao,
        variableTableColumnsDao,
        variableTextsDao,
    )
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertDocumentTemplate()
    insertVariableManifest()
    insertDocument()
  }

  @Nested
  inner class FetchByStableId {
    @Test
    fun `fetches the correct variable for a given stable ID`() {
      val variableName = "Variable 1"
      val stableId = "1"

      val variableId1 =
          insertNumberVariable(
              insertVariable(name = variableName, stableId = stableId, type = VariableType.Number)
          )
      val variableId2 =
          insertNumberVariable(
              insertVariable(
                  name = variableName,
                  stableId = stableId,
                  type = VariableType.Number,
                  replacesVariableId = variableId1,
              )
          )
      val variableId3 =
          insertNumberVariable(
              insertVariable(
                  name = variableName,
                  stableId = stableId,
                  type = VariableType.Number,
                  replacesVariableId = variableId2,
              )
          )

      val expected =
          NumberVariable(
              base =
                  BaseVariableProperties(
                      id = variableId3,
                      isRequired = false,
                      manifestId = null,
                      name = variableName,
                      position = 0,
                      replacesVariableId = variableId2,
                      stableId = StableId(stableId),
                  ),
              decimalPlaces = null,
              minValue = null,
              maxValue = null,
          )

      val actual = store.fetchByStableId(StableId(stableId))

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class FetchListByStableIds {
    @Test
    fun `fetches the correct variables for a given list of stable IDs`() {
      val variableName1 = "Variable 1"
      val variableName2 = "Variable 2"
      val variableName3 = "Variable 3"
      val stableId1 = "1"
      val stableId2 = "2"
      val stableId3 = "3"

      val variableId1 =
          insertNumberVariable(
              insertVariable(name = variableName1, stableId = stableId1, type = VariableType.Number)
          )
      val variableId2 =
          insertNumberVariable(
              insertVariable(
                  name = variableName1,
                  stableId = stableId1,
                  type = VariableType.Number,
                  replacesVariableId = variableId1,
              )
          )
      val variableId3 =
          insertNumberVariable(
              insertVariable(
                  name = variableName1,
                  stableId = stableId1,
                  type = VariableType.Number,
                  replacesVariableId = variableId2,
              )
          )

      val variableId4 =
          insertNumberVariable(
              insertVariable(name = variableName2, stableId = stableId2, type = VariableType.Number)
          )
      val variableId5 =
          insertNumberVariable(
              insertVariable(
                  name = variableName2,
                  stableId = stableId2,
                  type = VariableType.Number,
                  replacesVariableId = variableId4,
              )
          )

      val variableId6 =
          insertNumberVariable(
              insertVariable(name = variableName3, stableId = stableId3, type = VariableType.Number)
          )

      val expected =
          listOf(
              NumberVariable(
                  base =
                      BaseVariableProperties(
                          id = variableId3,
                          isRequired = false,
                          manifestId = null,
                          name = variableName1,
                          position = 0,
                          replacesVariableId = variableId2,
                          stableId = StableId(stableId1),
                      ),
                  decimalPlaces = null,
                  minValue = null,
                  maxValue = null,
              ),
              NumberVariable(
                  base =
                      BaseVariableProperties(
                          id = variableId5,
                          isRequired = false,
                          manifestId = null,
                          name = variableName2,
                          position = 0,
                          replacesVariableId = variableId4,
                          stableId = StableId(stableId2),
                      ),
                  decimalPlaces = null,
                  minValue = null,
                  maxValue = null,
              ),
              NumberVariable(
                  base =
                      BaseVariableProperties(
                          id = variableId6,
                          isRequired = false,
                          manifestId = null,
                          name = variableName3,
                          position = 0,
                          replacesVariableId = null,
                          stableId = StableId(stableId3),
                      ),
                  decimalPlaces = null,
                  minValue = null,
                  maxValue = null,
              ),
          )

      val actual =
          store.fetchListByStableIds(
              listOf(StableId(stableId1), StableId(stableId2), StableId(stableId3))
          )

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class FetchVariable {
    @Test
    fun `fetches nested sections`() {
      val sectionId1 =
          insertSectionVariable(
              id = insertVariable(name = "1", stableId = "A", type = VariableType.Section)
          )
      val sectionId2 =
          insertSectionVariable(
              id = insertVariable(name = "1.1", stableId = "B", type = VariableType.Section),
              parentId = sectionId1,
          )
      val sectionId3a =
          insertSectionVariable(
              id = insertVariable(name = "1.1.1", stableId = "C", type = VariableType.Section),
              parentId = sectionId2,
              renderHeading = false,
          )
      val sectionId3b =
          insertSectionVariable(
              id = insertVariable(name = "1.1.2", stableId = "D", type = VariableType.Section),
              parentId = sectionId2,
              renderHeading = false,
          )

      insertVariableManifestEntry(sectionId1)
      insertVariableManifestEntry(sectionId2)
      insertVariableManifestEntry(sectionId3a)
      insertVariableManifestEntry(sectionId3b)

      val expected =
          SectionVariable(
              base =
                  BaseVariableProperties(
                      id = sectionId1,
                      isRequired = false,
                      manifestId = inserted.variableManifestId,
                      name = "1",
                      position = 0,
                      stableId = StableId("A"),
                  ),
              renderHeading = true,
              children =
                  listOf(
                      SectionVariable(
                          BaseVariableProperties(
                              id = sectionId2,
                              isRequired = false,
                              manifestId = inserted.variableManifestId,
                              name = "1.1",
                              position = 1,
                              stableId = StableId("B"),
                          ),
                          renderHeading = true,
                          children =
                              listOf(
                                  SectionVariable(
                                      BaseVariableProperties(
                                          id = sectionId3a,
                                          isRequired = false,
                                          manifestId = inserted.variableManifestId,
                                          name = "1.1.1",
                                          position = 2,
                                          stableId = StableId("C"),
                                      ),
                                      renderHeading = false,
                                  ),
                                  SectionVariable(
                                      BaseVariableProperties(
                                          id = sectionId3b,
                                          isRequired = false,
                                          manifestId = inserted.variableManifestId,
                                          name = "1.1.2",
                                          position = 3,
                                          stableId = StableId("D"),
                                      ),
                                      renderHeading = false,
                                  ),
                              ),
                      )
                  ),
          )

      val actual = store.fetchOneVariable(sectionId1, inserted.variableManifestId)

      assertEquals(expected, actual)
    }

    @Test
    fun `detects cycles in parent-child relationships`() {
      val sectionId1 = insertVariable(type = VariableType.Section)
      val sectionId2 = insertVariable(type = VariableType.Section)
      val sectionId3 = insertVariable(type = VariableType.Section)

      insertSectionVariable(id = sectionId1, parentId = sectionId3)
      insertSectionVariable(id = sectionId2, parentId = sectionId1)
      insertSectionVariable(id = sectionId3, parentId = sectionId2)

      insertVariableManifestEntry(variableId = sectionId1)
      insertVariableManifestEntry(variableId = sectionId2)
      insertVariableManifestEntry(variableId = sectionId3)

      assertThrows<CircularReferenceException> {
        store.fetchOneVariable(sectionId1, inserted.variableManifestId)
      }
    }
  }

  @Nested
  inner class FetchDeliverableVariableIds {
    @Test
    fun `fetches the variable IDs associated to a particular deliverable`() {
      insertModule()
      val deliverableId1 = insertDeliverable()
      val deliverableId2 = insertDeliverable()

      val variableId1 =
          insertNumberVariable(
              insertVariable(
                  type = VariableType.Number,
                  deliverableId = deliverableId1,
                  deliverablePosition = 1,
                  deliverableQuestion = "Question 1",
                  name = "Variable 1",
                  stableId = "1",
              )
          )

      // This variable had a new version created
      val variableId2 =
          insertNumberVariable(
              insertVariable(
                  type = VariableType.Number,
                  deliverableId = deliverableId1,
                  // This version should appear first since the position is lower than variable 1's
                  deliverablePosition = 0,
                  deliverableQuestion = "Question 2",
                  isRequired = false,
                  name = "Variable 2",
                  stableId = "2",
              )
          )
      val variableId3 =
          insertNumberVariable(
              insertVariable(
                  type = VariableType.Number,
                  deliverableId = deliverableId1,
                  deliverablePosition = 0,
                  deliverableQuestion = "Question 3",
                  isRequired = true,
                  name = "Variable 2",
                  replacesVariableId = variableId2,
                  stableId = "2",
              )
          )

      // Another variable unrelated to the requested deliverable
      insertNumberVariable(
          insertVariable(
              type = VariableType.Number,
              deliverableId = deliverableId2,
              deliverablePosition = 0,
              stableId = "3",
          )
      )

      val expected =
          listOf(
              NumberVariable(
                  base =
                      BaseVariableProperties(
                          deliverablePositions = mapOf(deliverableId1 to 0),
                          deliverableQuestion = "Question 3",
                          id = variableId3,
                          isRequired = true,
                          manifestId = null,
                          name = "Variable 2",
                          position = 0,
                          stableId = StableId("2"),
                          replacesVariableId = variableId2,
                      ),
                  decimalPlaces = null,
                  minValue = null,
                  maxValue = null,
              ),
              NumberVariable(
                  base =
                      BaseVariableProperties(
                          deliverablePositions = mapOf(deliverableId1 to 1),
                          deliverableQuestion = "Question 1",
                          id = variableId1,
                          isRequired = false,
                          manifestId = null,
                          name = "Variable 1",
                          position = 0,
                          stableId = StableId("1"),
                      ),
                  decimalPlaces = null,
                  minValue = null,
                  maxValue = null,
              ),
          )

      val actual = store.fetchDeliverableVariables(deliverableId1)

      assertEquals(expected, actual)
    }

    @Test
    fun `fetches tables`() {
      insertModule()
      val deliverableId = insertDeliverable()
      val tableId =
          insertTableVariable(
              insertVariable(
                  deliverableId = deliverableId,
                  deliverablePosition = 1,
                  isList = true,
                  name = "Table",
                  type = VariableType.Table,
              )
          )
      val columnId =
          insertTextVariable(
              insertVariable(
                  deliverableId = deliverableId,
                  deliverablePosition = 2,
                  type = VariableType.Text,
              )
          )
      insertTableColumn(tableId, columnId)

      val expected =
          listOf(
              TableVariable(
                  base =
                      BaseVariableProperties(
                          deliverablePositions = mapOf(deliverableId to 1),
                          id = tableId,
                          isList = true,
                          manifestId = null,
                          name = "Table",
                          position = 0,
                          stableId = StableId("1"),
                      ),
                  tableStyle = VariableTableStyle.Horizontal,
                  columns =
                      listOf(
                          TableColumn(
                              isHeader = false,
                              variable =
                                  TextVariable(
                                      base =
                                          BaseVariableProperties(
                                              deliverablePositions = mapOf(deliverableId to 2),
                                              id = columnId,
                                              isList = false,
                                              manifestId = null,
                                              name = "Variable 2",
                                              position = 0,
                                              stableId = StableId("2"),
                                          ),
                                      textType = VariableTextType.SingleLine,
                                  ),
                          )
                      ),
              )
          )

      val actual = store.fetchDeliverableVariables(deliverableId)

      assertEquals(expected, actual)
    }

    @Test
    fun `does not return variables that have been replaced by newer ones`() {
      val stableId = "${UUID.randomUUID()}"

      insertModule()
      val oldDeliverableId = insertDeliverable()
      val oldVariableId =
          insertNumberVariable(deliverableId = oldDeliverableId, stableId = stableId)
      val newDeliverableId = insertDeliverable()
      insertNumberVariable(
          insertVariable(
              type = VariableType.Number,
              deliverableId = newDeliverableId,
              stableId = stableId,
              replacesVariableId = oldVariableId,
          )
      )

      assertEquals(emptyList<Variable>(), store.fetchDeliverableVariables(oldDeliverableId))
    }

    @Test
    fun `only returns internal-only variables if the user has permission`() {
      val stableId = "${UUID.randomUUID()}"

      insertModule()
      val deliverableId = insertDeliverable()
      val publicVariableId =
          insertNumberVariable(
              insertVariable(
                  type = VariableType.Number,
                  deliverableId = deliverableId,
                  stableId = "$stableId-1",
              )
          )
      val internalOnlyVariableId =
          insertNumberVariable(
              insertVariable(
                  type = VariableType.Number,
                  deliverableId = deliverableId,
                  stableId = "$stableId-2",
                  internalOnly = true,
              )
          )

      val expectedPublicVariable =
          NumberVariable(
              base =
                  BaseVariableProperties(
                      deliverablePositions = mapOf(deliverableId to 1),
                      id = publicVariableId,
                      isRequired = false,
                      manifestId = null,
                      name = "Variable 1",
                      position = 0,
                      stableId = StableId("$stableId-1"),
                  ),
              decimalPlaces = null,
              minValue = null,
              maxValue = null,
          )
      val expectedInternalOnlyVariable =
          NumberVariable(
              base =
                  BaseVariableProperties(
                      deliverablePositions = mapOf(deliverableId to 2),
                      id = internalOnlyVariableId,
                      internalOnly = true,
                      isRequired = false,
                      manifestId = null,
                      name = "Variable 2",
                      position = 0,
                      stableId = StableId("$stableId-2"),
                  ),
              decimalPlaces = null,
              minValue = null,
              maxValue = null,
          )

      every { user.canReadInternalOnlyVariables() } returns true

      assertEquals(
          listOf(expectedPublicVariable, expectedInternalOnlyVariable),
          store.fetchDeliverableVariables(deliverableId),
          "Result with permission to read internal-only variables",
      )

      every { user.canReadInternalOnlyVariables() } returns false

      assertEquals(
          listOf(expectedPublicVariable),
          store.fetchDeliverableVariables(deliverableId),
          "Result without permission to read internal-only variables",
      )

      every { user.canReadInternalOnlyVariables() } returns true

      assertEquals(
          listOf(expectedPublicVariable, expectedInternalOnlyVariable),
          store.fetchDeliverableVariables(deliverableId),
          "Result with permission to read internal-only variables after unprivileged read",
      )
    }
  }

  @Nested
  inner class FetchUsedVariables {
    @Test
    fun `fetches the variables used within sections for a particular document`() {
      val manifestId = insertVariableManifest()
      val manifestIdOther = insertVariableManifest()

      val projectId = insertProject()
      val projectIdOther = insertProject()

      insertDocument(variableManifestId = manifestIdOther, projectId = projectIdOther)
      val documentId = insertDocument(variableManifestId = manifestId, projectId = projectId)
      insertDocument(variableManifestId = manifestIdOther, projectId = projectId)
      insertDocument(variableManifestId = manifestId, projectId = projectIdOther)

      val variableIdOutdated =
          insertNumberVariable(
              id =
                  insertVariable(
                      name = "Number Variable",
                      stableId = "1",
                      type = VariableType.Number,
                  ),
              maxValue = BigDecimal(10),
          )

      val parentSectionVariableId =
          insertVariableManifestEntry(
              manifestId = manifestId,
              variableId =
                  insertSectionVariable(
                      id =
                          insertVariable(
                              name = "Parent section",
                              stableId = "100",
                              type = VariableType.Section,
                          )
                  ),
              position = 1,
          )
      val childSectionVariableId =
          insertVariableManifestEntry(
              manifestId = manifestId,
              variableId =
                  insertSectionVariable(
                      id =
                          insertVariable(
                              name = "Child section",
                              stableId = "101",
                              type = VariableType.Section,
                          ),
                      parentId = parentSectionVariableId,
                      renderHeading = true,
                  ),
              position = 2,
          )
      val grandchildSectionVariableId =
          insertVariableManifestEntry(
              manifestId = manifestId,
              variableId =
                  insertSectionVariable(
                      id =
                          insertVariable(
                              name = "Grandchild section",
                              stableId = "1001",
                              type = VariableType.Section,
                          ),
                      parentId = childSectionVariableId,
                      renderHeading = false,
                  ),
              position = 3,
          )

      val otherSectionVariableId =
          insertVariableManifestEntry(
              manifestId = manifestIdOther,
              variableId =
                  insertSectionVariable(
                      id =
                          insertVariable(
                              name = "Parent section - other manifest",
                              stableId = "200",
                              type = VariableType.Section,
                          )
                  ),
              position = 1,
          )

      // The variable is injected into the section along with some text
      insertSectionValue(
          listPosition = 0,
          projectId = projectId,
          variableId = grandchildSectionVariableId,
          textValue = "Section text",
      )
      insertSectionValue(
          listPosition = 1,
          projectId = projectId,
          variableId = grandchildSectionVariableId,
          usedVariableId = variableIdOutdated,
      )

      // Variable is updated at some point
      val variableIdCurrent =
          insertNumberVariable(
              id =
                  insertVariable(
                      name = "Number Variable",
                      replacesVariableId = variableIdOutdated,
                      stableId = "1",
                      type = VariableType.Number,
                  ),
              maxValue = BigDecimal(5),
          )

      // Other injections for other projects and/or documents
      insertSectionValue(
          listPosition = 0,
          projectId = projectIdOther,
          variableId = grandchildSectionVariableId,
          textValue = "Section text",
      )
      insertSectionValue(
          listPosition = 1,
          projectId = projectIdOther,
          variableId = grandchildSectionVariableId,
          usedVariableId = variableIdCurrent,
      )
      insertSectionValue(
          listPosition = 1,
          projectId = projectId,
          variableId = otherSectionVariableId,
          usedVariableId = variableIdCurrent,
      )
      insertSectionValue(
          listPosition = 1,
          projectId = projectIdOther,
          variableId = otherSectionVariableId,
          usedVariableId = variableIdOutdated,
      )

      val actual = store.fetchUsedVariables(documentId)
      val expected =
          listOf<Variable>(
              NumberVariable(
                  base =
                      BaseVariableProperties(
                          id = variableIdOutdated,
                          isRequired = false,
                          manifestId = null,
                          name = "Number Variable",
                          position = 0,
                          stableId = StableId("1"),
                      ),
                  decimalPlaces = null,
                  minValue = null,
                  maxValue = BigDecimal(10),
              )
          )

      assertEquals(expected, actual, "Fetch used variables for document")
    }
  }

  @Nested
  inner class ClearCache {
    @Test
    fun `test that cache is properly cleared`() {
      val variableId = insertTextVariable(insertVariable())
      insertModule()
      val deliverableId = insertDeliverable()
      insertDeliverableVariable(position = 0)

      // add to cache
      val beforeVariable = store.fetchOneVariable(variableId)
      assertEquals(
          beforeVariable.deliverablePositions[deliverableId],
          0,
          "Position after insertion should be correct",
      )

      with(DELIVERABLE_VARIABLES) {
        dslContext
            .update(this)
            .set(POSITION, 1)
            .where(DELIVERABLE_ID.eq(deliverableId))
            .and(VARIABLE_ID.eq(variableId))
            .execute()
      }

      val nonUpdatedVariable = store.fetchOneVariable(variableId)
      assertEquals(
          nonUpdatedVariable.deliverablePositions[deliverableId],
          0,
          "Position after update should be from cache, not db",
      )

      store.clearCache()

      val updatedVariable = store.fetchOneVariable(variableId)
      assertEquals(
          updatedVariable.deliverablePositions[deliverableId],
          1,
          "Position after cache clear should be updated from db",
      )
    }
  }
}
