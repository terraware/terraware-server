package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.documentproducer.model.BaseVariableProperties
import com.terraformation.backend.documentproducer.model.SectionVariable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VariableStoreTest : DatabaseTest() {
  private val store: VariableStore by lazy {
    VariableStore(
        dslContext,
        variableNumbersDao,
        variablesDao,
        variableSectionRecommendationsDao,
        variableSectionsDao,
        variableSelectsDao,
        variableSelectOptionsDao,
        variableTablesDao,
        variableTableColumnsDao,
        variableTextsDao)
  }

  @Nested
  inner class FetchVariable {
    @Test
    fun `fetches nested sections`() {
      val sectionId1 = insertSectionVariable()
      val sectionId2 = insertSectionVariable(parentId = sectionId1)
      val sectionId3a = insertSectionVariable(parentId = sectionId2, renderHeading = false)
      val sectionId3b = insertSectionVariable(parentId = sectionId2, renderHeading = false)

      insertVariableManifestEntry(sectionId1, name = "1", stableId = "A")
      insertVariableManifestEntry(sectionId2, name = "1.1", stableId = "B")
      insertVariableManifestEntry(sectionId3a, name = "1.1.1", stableId = "C")
      insertVariableManifestEntry(sectionId3b, name = "1.1.2", stableId = "D")

      val expected =
          SectionVariable(
              base =
                  BaseVariableProperties(
                      id = sectionId1,
                      manifestId = cannedVariableManifestId,
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
                              manifestId = cannedVariableManifestId,
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
                                          manifestId = cannedVariableManifestId,
                                          name = "1.1.1",
                                          position = 2,
                                          stableId = "C",
                                      ),
                                      renderHeading = false,
                                  ),
                                  SectionVariable(
                                      BaseVariableProperties(
                                          id = sectionId3b,
                                          manifestId = cannedVariableManifestId,
                                          name = "1.1.2",
                                          position = 3,
                                          stableId = "D",
                                      ),
                                      renderHeading = false)))))

      val actual = store.fetchVariable(cannedVariableManifestId, sectionId1)

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
        store.fetchVariable(cannedVariableManifestId, sectionId1)
      }
    }
  }
}
