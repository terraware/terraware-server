package com.terraformation.backend.documentproducer

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.documentproducer.db.DocumentNotFoundException
import com.terraformation.backend.documentproducer.db.DocumentStore
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.BaseVariableProperties
import com.terraformation.backend.documentproducer.model.NumberVariable
import com.terraformation.backend.documentproducer.model.SectionVariable
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.mockUser
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VariableServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val service: VariableService by lazy {
    VariableService(
        DocumentStore(
            clock, documentSavedVersionsDao, documentsDao, dslContext, documentTemplatesDao),
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
            variableTextsDao),
        VariableValueStore(
            clock,
            documentsDao,
            dslContext,
            eventPublisher,
            variableImageValuesDao,
            variableLinkValuesDao,
            variablesDao,
            variableSectionValuesDao,
            variableSelectOptionValuesDao,
            variableValuesDao,
            variableValueTableRowsDao))
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertDocumentTemplate()

    every { user.canReadDocument(any()) } returns true
  }

  @Nested
  inner class FetchDocumentVariables {
    @Test
    fun `fetches the expected variables associated to a document`() {
      val manifestId1 = insertVariableManifest()
      val manifestId2 = insertVariableManifest()

      val projectId1 = insertProject()
      val projectId2 = insertProject()

      insertDocument(variableManifestId = manifestId2, projectId = projectId2)
      val documentId = insertDocument(variableManifestId = manifestId1, projectId = projectId1)
      insertDocument(variableManifestId = manifestId2, projectId = projectId1)
      insertDocument(variableManifestId = manifestId1, projectId = projectId2)

      val variableIdOutdated =
          insertNumberVariable(
              id =
                  insertVariable(
                      name = "Number Variable", stableId = "1", type = VariableType.Number),
              maxValue = BigDecimal(10))

      val parentSectionVariableId =
          insertVariableManifestEntry(
              manifestId = manifestId1,
              variableId =
                  insertSectionVariable(
                      id =
                          insertVariable(
                              name = "Parent section",
                              stableId = "100",
                              type = VariableType.Section)),
              position = 1)
      val childSectionVariableId =
          insertVariableManifestEntry(
              manifestId = manifestId1,
              variableId =
                  insertSectionVariable(
                      id =
                          insertVariable(
                              name = "Child section",
                              stableId = "101",
                              type = VariableType.Section),
                      parentId = parentSectionVariableId,
                      renderHeading = false),
              position = 2)

      val otherSectionVariableId =
          insertVariableManifestEntry(
              manifestId = manifestId2,
              variableId =
                  insertSectionVariable(
                      id =
                          insertVariable(
                              name = "Parent section - other manifest",
                              stableId = "200",
                              type = VariableType.Section)),
              position = 1)

      // The variable is injected into the section along with some text
      insertSectionValue(
          listPosition = 0,
          projectId = projectId1,
          variableId = childSectionVariableId,
          textValue = "Section text")
      insertSectionValue(
          listPosition = 1,
          projectId = projectId1,
          variableId = childSectionVariableId,
          usedVariableId = variableIdOutdated)

      // Variable is updated at some point
      val variableIdCurrent =
          insertNumberVariable(
              id =
                  insertVariable(
                      name = "Number Variable",
                      replacesVariableId = variableIdOutdated,
                      stableId = "1",
                      type = VariableType.Number),
              maxValue = BigDecimal(5))

      // Other injections for other projects and/or documents
      insertSectionValue(
          listPosition = 0,
          projectId = projectId2,
          variableId = childSectionVariableId,
          textValue = "Section text")
      insertSectionValue(
          listPosition = 1,
          projectId = projectId2,
          variableId = childSectionVariableId,
          usedVariableId = variableIdCurrent)
      insertSectionValue(
          listPosition = 1,
          projectId = projectId1,
          variableId = otherSectionVariableId,
          usedVariableId = variableIdCurrent)
      insertSectionValue(
          listPosition = 1,
          projectId = projectId2,
          variableId = otherSectionVariableId,
          usedVariableId = variableIdOutdated)

      val actual = service.fetchDocumentVariables(documentId)
      val expected =
          listOf<Variable>(
              SectionVariable(
                  base =
                      BaseVariableProperties(
                          id = parentSectionVariableId,
                          isRequired = false,
                          manifestId = manifestId1,
                          name = "Parent section",
                          position = 1,
                          stableId = "100",
                      ),
                  renderHeading = true,
                  children =
                      listOf(
                          SectionVariable(
                              BaseVariableProperties(
                                  id = childSectionVariableId,
                                  isRequired = false,
                                  manifestId = manifestId1,
                                  name = "Child section",
                                  position = 2,
                                  stableId = "101",
                              ),
                              renderHeading = false,
                              children = emptyList()))),
              NumberVariable(
                  base =
                      BaseVariableProperties(
                          id = variableIdOutdated,
                          isRequired = false,
                          manifestId = null,
                          name = "Number Variable",
                          position = 0,
                          stableId = "1"),
                  decimalPlaces = 0,
                  minValue = null,
                  maxValue = BigDecimal(10)))

      assertEquals(expected, actual, "Fetch variables for document")
    }

    @Test
    fun `throws an error when there's no permission to read the document`() {
      every { user.canReadDocument(any()) } returns false

      assertThrows<DocumentNotFoundException> { service.fetchDocumentVariables(DocumentId(1)) }
    }
  }
}
