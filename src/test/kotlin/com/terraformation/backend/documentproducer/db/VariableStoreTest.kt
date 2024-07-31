package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.documentproducer.model.BaseVariableProperties
import com.terraformation.backend.documentproducer.model.NumberVariable
import com.terraformation.backend.documentproducer.model.SectionVariable
import com.terraformation.backend.mockUser
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
        variableTextsDao)
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
              insertVariable(name = variableName, stableId = stableId, type = VariableType.Number))
      val variableId2 =
          insertNumberVariable(
              insertVariable(
                  name = variableName,
                  stableId = stableId,
                  type = VariableType.Number,
                  replacesVariableId = variableId1))
      val variableId3 =
          insertNumberVariable(
              insertVariable(
                  name = variableName,
                  stableId = stableId,
                  type = VariableType.Number,
                  replacesVariableId = variableId2))

      val expected =
          NumberVariable(
              base =
                  BaseVariableProperties(
                      id = variableId3,
                      manifestId = null,
                      name = variableName,
                      position = 0,
                      replacesVariableId = variableId2,
                      stableId = stableId,
                  ),
              decimalPlaces = 0,
              minValue = null,
              maxValue = null)

      val actual = store.fetchByStableId(stableId)

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class FetchVariable {
    @Test
    fun `fetches nested sections`() {
      val sectionId1 =
          insertSectionVariable(
              id = insertVariable(name = "1", stableId = "A", type = VariableType.Section))
      val sectionId2 =
          insertSectionVariable(
              id = insertVariable(name = "1.1", stableId = "B", type = VariableType.Section),
              parentId = sectionId1)
      val sectionId3a =
          insertSectionVariable(
              id = insertVariable(name = "1.1.1", stableId = "C", type = VariableType.Section),
              parentId = sectionId2,
              renderHeading = false)
      val sectionId3b =
          insertSectionVariable(
              id = insertVariable(name = "1.1.2", stableId = "D", type = VariableType.Section),
              parentId = sectionId2,
              renderHeading = false)

      insertVariableManifestEntry(sectionId1)
      insertVariableManifestEntry(sectionId2)
      insertVariableManifestEntry(sectionId3a)
      insertVariableManifestEntry(sectionId3b)

      val expected =
          SectionVariable(
              base =
                  BaseVariableProperties(
                      id = sectionId1,
                      manifestId = inserted.variableManifestId,
                      name = "1",
                      position = 0,
                      stableId = "A",
                  ),
              renderHeading = true,
              children =
                  listOf(
                      SectionVariable(
                          BaseVariableProperties(
                              id = sectionId2,
                              manifestId = inserted.variableManifestId,
                              name = "1.1",
                              position = 1,
                              stableId = "B",
                          ),
                          renderHeading = true,
                          children =
                              listOf(
                                  SectionVariable(
                                      BaseVariableProperties(
                                          id = sectionId3a,
                                          manifestId = inserted.variableManifestId,
                                          name = "1.1.1",
                                          position = 2,
                                          stableId = "C",
                                      ),
                                      renderHeading = false,
                                  ),
                                  SectionVariable(
                                      BaseVariableProperties(
                                          id = sectionId3b,
                                          manifestId = inserted.variableManifestId,
                                          name = "1.1.2",
                                          position = 3,
                                          stableId = "D",
                                      ),
                                      renderHeading = false)))))

      val actual = store.fetchVariable(sectionId1, inserted.variableManifestId)

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
        store.fetchVariable(sectionId1, inserted.variableManifestId)
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
                  deliverablePosition = 0))
      val variableId2 =
          insertNumberVariable(
              insertVariable(
                  type = VariableType.Number,
                  deliverableId = deliverableId1,
                  deliverablePosition = 1))
      insertNumberVariable(
          insertVariable(
              type = VariableType.Number, deliverableId = deliverableId2, deliverablePosition = 0))

      val expected =
          listOf(
              NumberVariable(
                  base =
                      BaseVariableProperties(
                          deliverableId = deliverableId1,
                          deliverablePosition = 0,
                          id = variableId1,
                          manifestId = null,
                          name = "Variable 1",
                          position = 0,
                          stableId = "1",
                      ),
                  decimalPlaces = 0,
                  minValue = null,
                  maxValue = null),
              NumberVariable(
                  base =
                      BaseVariableProperties(
                          deliverableId = deliverableId1,
                          deliverablePosition = 1,
                          id = variableId2,
                          manifestId = null,
                          name = "Variable 2",
                          position = 0,
                          stableId = "2",
                      ),
                  decimalPlaces = 0,
                  minValue = null,
                  maxValue = null),
          )

      val actual = store.fetchDeliverableVariables(deliverableId1)

      assertEquals(expected, actual)
    }
  }
}
