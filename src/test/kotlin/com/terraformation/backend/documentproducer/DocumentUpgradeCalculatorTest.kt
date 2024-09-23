package com.terraformation.backend.documentproducer

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableInjectionDisplayStyle
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableUsageType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.documentproducer.db.DocumentStore
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.AppendValueOperation
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.DeleteValueOperation
import com.terraformation.backend.documentproducer.model.ExistingSectionValue
import com.terraformation.backend.documentproducer.model.NewNumberValue
import com.terraformation.backend.documentproducer.model.NewSectionValue
import com.terraformation.backend.documentproducer.model.NewTableValue
import com.terraformation.backend.documentproducer.model.NewTextValue
import com.terraformation.backend.documentproducer.model.SectionValueText
import com.terraformation.backend.documentproducer.model.SectionValueVariable
import com.terraformation.backend.documentproducer.model.UpdateValueOperation
import com.terraformation.backend.documentproducer.model.ValueOperation
import com.terraformation.backend.mockUser
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DocumentUpgradeCalculatorTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()

  private val documentStore: DocumentStore by lazy {
    DocumentStore(clock, documentSavedVersionsDao, documentsDao, dslContext, documentTemplatesDao)
  }

  private val variableStore: VariableStore by lazy {
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
  private val variableValueStore: VariableValueStore by lazy {
    VariableValueStore(
        clock,
        dslContext,
        TestEventPublisher(),
        variableImageValuesDao,
        variableLinkValuesDao,
        variablesDao,
        variableSectionValuesDao,
        variableSelectOptionValuesDao,
        variableValuesDao,
        variableValueTableRowsDao)
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertDocumentTemplate()
    insertVariableManifest()
    insertDocument()

    every { user.canReadDocument(any()) } returns true
  }

  @Test
  fun `does not return operations for values that no longer validate`() {
    val oldVariableId = insertVariableManifestEntry(insertNumberVariable())

    insertValue(variableId = oldVariableId, numberValue = BigDecimal.ONE)

    val newManifestId = insertVariableManifest()
    insertVariableManifestEntry(
        insertNumberVariable(
            insertVariable(type = VariableType.Number, replacesVariableId = oldVariableId),
            minValue = BigDecimal.TEN),
        manifestId = newManifestId)

    assertEquals(emptyList<ValueOperation>(), calculateOperations(newManifestId))
  }

  // This just tests that values are converted as part of the upgrade calculations; the conversion
  // logic is covered in VariableTest.
  @Test
  fun `converts value to new variable type`() {
    val oldVariableId = insertVariableManifestEntry(insertNumberVariable(decimalPlaces = 5))
    insertValue(variableId = oldVariableId, numberValue = BigDecimal("1.23456"))

    val newManifestId = insertVariableManifest()
    val newVariableId =
        insertVariableManifestEntry(
            insertTextVariable(
                insertVariable(type = VariableType.Text, replacesVariableId = oldVariableId)),
            manifestId = newManifestId)

    assertEquals(
        listOf(AppendValueOperation(NewTextValue(newValueProps(newVariableId), "1.23456"))),
        calculateOperations(newManifestId))
  }

  @Test
  fun `appends valid list items in same order as original list`() {
    val oldVariableId =
        insertVariableManifestEntry(
            insertNumberVariable(insertVariable(type = VariableType.Number, isList = true)))

    insertValue(variableId = oldVariableId, listPosition = 0, numberValue = BigDecimal(0))
    insertValue(variableId = oldVariableId, listPosition = 1, numberValue = BigDecimal(1))
    insertValue(variableId = oldVariableId, listPosition = 2, numberValue = BigDecimal(100))
    insertValue(variableId = oldVariableId, listPosition = 3, numberValue = BigDecimal(3))

    val newManifestId = insertVariableManifest()
    val newVariableId =
        insertVariableManifestEntry(
            insertNumberVariable(
                insertVariable(
                    type = VariableType.Number, isList = true, replacesVariableId = oldVariableId),
                maxValue = BigDecimal.TEN),
            manifestId = newManifestId)

    assertEquals(
        listOf(
            AppendValueOperation(
                NewNumberValue(newValueProps(newVariableId, listPosition = 0), BigDecimal(0))),
            AppendValueOperation(
                NewNumberValue(newValueProps(newVariableId, listPosition = 1), BigDecimal(1))),
            // List position is preserved across value conversion, so we expect to skip position 2
            // here; it is ignored by append operations so this won't result in a hole in the final
            // result.
            AppendValueOperation(
                NewNumberValue(newValueProps(newVariableId, listPosition = 3), BigDecimal(3))),
        ),
        calculateOperations(newManifestId))
  }

  @Test
  fun `populates new table rows with values from old columns`() {
    // Original table has one obsolete column that won't appear in the new table version, and one
    // outdated column that will appear, but with different settings (a minimum value).
    val oldManifestId = insertVariableManifest()
    val oldTableVariableId =
        insertVariableManifestEntry(insertTableVariable(), manifestId = oldManifestId)
    val oldTableObsoleteColumnId =
        insertVariableManifestEntry(insertTextVariable(), manifestId = oldManifestId)
    insertTableColumn(oldTableVariableId, oldTableObsoleteColumnId)
    val oldTableOutdatedColumnId =
        insertVariableManifestEntry(insertNumberVariable(), manifestId = oldManifestId)
    insertTableColumn(oldTableVariableId, oldTableOutdatedColumnId)

    val newManifestId = insertVariableManifest()
    val newTableVariableId =
        insertVariableManifestEntry(
            insertTableVariable(
                insertVariable(type = VariableType.Table, replacesVariableId = oldTableVariableId)),
            manifestId = newManifestId)
    val newTableUpdatedColumnId =
        insertVariableManifestEntry(
            insertNumberVariable(
                insertVariable(
                    type = VariableType.Number, replacesVariableId = oldTableOutdatedColumnId),
                minValue = BigDecimal.TEN),
            manifestId = newManifestId)
    insertTableColumn(newTableVariableId, newTableUpdatedColumnId)

    val documentId = insertDocument(variableManifestId = oldManifestId)

    val oldTableRowId1 = insertValue(variableId = oldTableVariableId, listPosition = 0)
    val oldRowVal1Col1 =
        insertValue(variableId = oldTableObsoleteColumnId, textValue = "obsolete 1")
    insertValueTableRow(oldRowVal1Col1, oldTableRowId1)
    val oldRowVal1Col2 =
        insertValue(
            variableId = oldTableOutdatedColumnId,
            numberValue = BigDecimal(50),
            citation = "citation")
    insertValueTableRow(oldRowVal1Col2, oldTableRowId1)

    val oldTableRowId2 = insertValue(variableId = oldTableVariableId, listPosition = 1)
    val oldRowVal2Col1 =
        insertValue(variableId = oldTableObsoleteColumnId, textValue = "obsolete 2")
    insertValueTableRow(oldRowVal2Col1, oldTableRowId2)
    val oldRowVal2Col2 =
        insertValue(variableId = oldTableOutdatedColumnId, numberValue = BigDecimal(5))
    insertValueTableRow(oldRowVal2Col2, oldTableRowId2)

    assertEquals(
        listOf(
            // Delete old rows
            DeleteValueOperation(inserted.projectId, oldTableRowId1),
            DeleteValueOperation(inserted.projectId, oldTableRowId2),
            // Append new rows
            AppendValueOperation(NewTableValue(newValueProps(newTableVariableId))),
            AppendValueOperation(
                NewNumberValue(
                    newValueProps(newTableUpdatedColumnId, citation = "citation"), BigDecimal(50))),
            // Delete value from deleted column
            DeleteValueOperation(inserted.projectId, oldRowVal1Col2),
            // Empty row because the old one didn't have any values to carry forward.
            AppendValueOperation(NewTableValue(newValueProps(newTableVariableId))),
            // Delete value from deleted column
            DeleteValueOperation(inserted.projectId, oldRowVal2Col2),
        ),
        calculateOperations(newManifestId, documentId))
  }

  @Test
  fun `updates variable references in sections to use new variable IDs`() {
    val oldManifestId = insertVariableManifest()
    val outdatedVariableId =
        insertVariableManifestEntry(insertTextVariable(), manifestId = oldManifestId)
    val unmodifiedVariableId =
        insertVariableManifestEntry(insertNumberVariable(), manifestId = oldManifestId)
    val obsoleteVariableId =
        insertVariableManifestEntry(insertTextVariable(), manifestId = oldManifestId)
    val sectionVariableId = insertVariableManifestEntry(insertSectionVariable())
    val newManifestId = insertVariableManifest()
    val updatedVariableId =
        insertVariableManifestEntry(
            insertTextVariable(
                insertVariable(type = VariableType.Text, replacesVariableId = outdatedVariableId)),
            manifestId = newManifestId)
    insertVariableManifestEntry(unmodifiedVariableId, manifestId = newManifestId)
    insertVariableManifestEntry(sectionVariableId, manifestId = newManifestId)

    val documentId = insertDocument(variableManifestId = oldManifestId)
    val outdatedValueId =
        insertSectionValue(sectionVariableId, listPosition = 0, usedVariableId = outdatedVariableId)
    val obsoleteValueId =
        insertSectionValue(sectionVariableId, listPosition = 1, usedVariableId = obsoleteVariableId)
    insertSectionValue(sectionVariableId, listPosition = 2, textValue = "some text")
    insertSectionValue(sectionVariableId, listPosition = 3, usedVariableId = unmodifiedVariableId)

    assertEquals(
        listOf(
            UpdateValueOperation(
                ExistingSectionValue(
                    BaseVariableValueProperties(
                        outdatedValueId, inserted.projectId, 0, sectionVariableId, null),
                    SectionValueVariable(
                        updatedVariableId,
                        VariableUsageType.Injection,
                        VariableInjectionDisplayStyle.Block))),
            DeleteValueOperation(inserted.projectId, obsoleteValueId),
        ),
        calculateOperations(newManifestId, documentId))
  }

  @Test
  fun `subsection values variable references updated to new variable IDs`() {
    val oldManifestId = insertVariableManifest()
    val parentSectionVariableId =
        insertVariableManifestEntry(insertSectionVariable(), manifestId = oldManifestId)
    val subSectionVariableId =
        insertVariableManifestEntry(
            insertSectionVariable(parentId = parentSectionVariableId), manifestId = oldManifestId)

    val newManifestId = insertVariableManifest()
    val newParentSectionVariableId =
        insertVariableManifestEntry(
            insertSectionVariable(
                insertVariable(
                    type = VariableType.Section, replacesVariableId = parentSectionVariableId)),
            manifestId = newManifestId)
    val newSubSectionVariableId =
        insertVariableManifestEntry(
            insertSectionVariable(
                insertVariable(
                    type = VariableType.Section, replacesVariableId = subSectionVariableId),
                parentId = newParentSectionVariableId),
            manifestId = newManifestId)
    insertVariableManifestEntry(
        insertSectionVariable(parentId = newParentSectionVariableId), manifestId = newManifestId)

    val documentId = insertDocument(variableManifestId = oldManifestId)
    val sectionValueId = insertSectionValue(subSectionVariableId, textValue = "some text")

    assertEquals(
        listOf(
            AppendValueOperation(
                NewSectionValue(
                    BaseVariableValueProperties(
                        null, inserted.projectId, 0, newSubSectionVariableId, null),
                    SectionValueText("some text"),
                )),
            DeleteValueOperation(inserted.projectId, sectionValueId)),
        calculateOperations(newManifestId, documentId))
  }

  private fun calculateOperations(
      manifestId: VariableManifestId,
      documentId: DocumentId = inserted.documentId
  ) =
      DocumentUpgradeCalculator(
              documentId,
              manifestId,
              documentStore,
              variableManifestsDao,
              variableStore,
              variableValueStore)
          .calculateOperations()

  private fun newValueProps(
      variableId: VariableId,
      projectId: ProjectId = inserted.projectId,
      listPosition: Int = 0,
      rowValueId: VariableValueId? = null,
      citation: String? = null,
  ): BaseVariableValueProperties<Nothing?> {
    return BaseVariableValueProperties(
        citation = citation,
        id = null,
        listPosition = listPosition,
        projectId = projectId,
        variableId = variableId,
        rowValueId = rowValueId,
    )
  }
}
