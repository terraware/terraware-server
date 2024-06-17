package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.documentproducer.model.BaseVariableProperties
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
    insertUser()
    insertOrganization()
    insertProject()
    insertDocumentTemplate()
    insertVariableManifest()
    insertDocument()
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

      val actual = store.fetchVariableForManifest(inserted.variableManifestId, sectionId1)

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
        store.fetchVariableForManifest(inserted.variableManifestId, sectionId1)
      }
    }
  }
}
