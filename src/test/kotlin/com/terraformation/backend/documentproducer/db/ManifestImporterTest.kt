package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableInjectionDisplayStyle
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableUsageType
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionDefaultValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariablesRow
import com.terraformation.backend.db.docprod.tables.records.VariableSectionDefaultValuesRecord
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_MANIFEST_ENTRIES
import com.terraformation.backend.documentproducer.db.manifest.ManifestImporter
import com.terraformation.backend.documentproducer.db.variable.VariableImporter
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ManifestImporterTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val messages = Messages()
  private val clock = TestClock()

  private val deliverableStore: DeliverableStore by lazy { DeliverableStore(dslContext) }

  private val variableManifestStore: VariableManifestStore by lazy {
    VariableManifestStore(
        clock,
        documentTemplatesDao,
        dslContext,
        variableManifestsDao,
        variableManifestEntriesDao,
    )
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
        variableTextsDao,
    )
  }

  private val importer: ManifestImporter by lazy {
    ManifestImporter(dslContext, messages, variableManifestStore, variableStore)
  }

  private val variableImporter: VariableImporter by lazy {
    VariableImporter(deliverableStore, dslContext, messages, variableStore)
  }

  @BeforeEach
  fun setUp() {
    insertDocumentTemplate()
  }

  @Nested
  inner class UploadManifest {
    private val header =
        "Name,ID,Description,Recommended variables,Parent,Non-numbered section?,Default Text"

    @BeforeEach
    fun setUp() {
      every { user.canCreateVariableManifest() } returns true
    }

    @Test
    fun `detects duplicate stable IDs`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv = header + "\nName X,Duplicate ID,,,,," + "\nName Y,Duplicate ID,,,,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: ID must be unique across the entire manifest, Field: ID, Value: Duplicate ID, Position: 3"
          ),
          importResult.errors,
      )
    }

    @Test
    fun `correctly identifies when there is a non-unique top level input name`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nProject Details,1,,,,," +
              "\nProject Details,2,,,,," +
              "\nSummary Description of the Project,3,,,Project Details,," +
              "\nIntroduction,4,,,Summary Description of the Project,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Top Level Variable Name must be unique, Field: Name, Value: Project Details, Position: 3"
          ),
          importResult.errors,
          "Non-Unique Input Names",
      )
    }

    @Test
    fun `imports section variables correctly`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nProject Details,1,,,,," +
              "\nSummary Description of the Project,2,,,Project Details,," +
              "\nIntroduction ,3,,,Summary Description of the Project,," +
              "\nBrief description of the project,4,,,Introduction,yes," +
              "\nCurrent status of the project and the area it covers.,5,,,Introduction,yes," +
              "\nProject Details,6,,,Summary Description of the Project,," +
              "\nDetailed explanation,7,,,Project Details,yes," +
              "\nInformation about the land,8,,,Project Details,yes," +
              "\nProject Management,9,,,Summary Description of the Project,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      // Flatten the section variables into a shape that we can use to easily verify the hierarchy
      val flatVariables =
          variableSectionsDao.findAll().map { variableSectionsRow ->
            FlatVariable(
                variableId = variableSectionsRow.variableId!!,
                variableName =
                    variablesDao.fetchById(variableSectionsRow.variableId!!).single().name,
                parentId = variableSectionsRow.parentVariableId,
                parentVariableName =
                    variableSectionsRow.parentVariableId?.let {
                      variablesDao.fetchById(it).single().name
                    },
            )
          }

      fun getSectionVariableByNameAndParent(
          name: String,
          parent: String? = null,
      ): VariableSectionsRow? =
          flatVariables
              .find { it.variableName == name && it.parentVariableName == parent }
              ?.let { variableSectionsDao.fetchOneByVariableId(it.variableId) }

      val actualLevel1SectionVariable = getSectionVariableByNameAndParent("Project Details")
      val expectedLevel1SectionVariable =
          VariableSectionsRow(variableTypeId = VariableType.Section, renderHeading = true)

      val actualLevel2SectionVariable =
          getSectionVariableByNameAndParent("Summary Description of the Project", "Project Details")
      val expectedLevel2SectionVariable =
          VariableSectionsRow(
              variableTypeId = VariableType.Section,
              parentVariableId = actualLevel1SectionVariable!!.variableId,
              parentVariableTypeId = VariableType.Section,
              renderHeading = true,
          )

      val actualLevel3SectionVariable =
          getSectionVariableByNameAndParent("Project Details", "Summary Description of the Project")
      val expectedLevel3SectionVariable =
          VariableSectionsRow(
              variableTypeId = VariableType.Section,
              parentVariableId = actualLevel2SectionVariable!!.variableId,
              parentVariableTypeId = VariableType.Section,
              renderHeading = true,
          )

      // This is a non-numbered section, there should be no header rendering
      val actualLevel4SectionVariable =
          getSectionVariableByNameAndParent("Information about the land", "Project Details")
      val expectedLevel4SectionVariable =
          VariableSectionsRow(
              variableTypeId = VariableType.Section,
              parentVariableId = actualLevel3SectionVariable!!.variableId,
              parentVariableTypeId = VariableType.Section,
              renderHeading = false,
          )

      assertEquals(emptyList<String>(), importResult.errors, "no errors")

      // Ignore the variable IDs in the actual variables because they are being validated implicitly
      // through the child/parent relationship
      assertEquals(
          expectedLevel1SectionVariable,
          actualLevel1SectionVariable.copy(variableId = null),
          "level 1 section variable",
      )
      assertEquals(
          expectedLevel2SectionVariable,
          actualLevel2SectionVariable.copy(variableId = null),
          "level 2 section variable",
      )
      assertEquals(
          expectedLevel3SectionVariable,
          actualLevel3SectionVariable.copy(variableId = null),
          "level 3 section variable",
      )
      assertEquals(
          expectedLevel4SectionVariable,
          actualLevel4SectionVariable?.copy(variableId = null),
          "level 4 section variable",
      )
    }

    @Test
    fun `imports section default values with multiple variables`() {
      insertTextVariable(
          insertVariable(name = "Text Variable A", stableId = "1001", type = VariableType.Text)
      )
      insertTextVariable(
          insertVariable(name = "Text Variable B", stableId = "1002", type = VariableType.Text)
      )
      insertTextVariable(
          insertVariable(name = "Text Variable C", stableId = "1003", type = VariableType.Text)
      )
      insertTextVariable(
          insertVariable(name = "Text Variable D", stableId = "1004", type = VariableType.Text)
      )
      insertTextVariable(
          insertVariable(name = "Text Variable E", stableId = "1005", type = VariableType.Text)
      )

      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          "$header\nSection,1,,,,Yes,Default text with {{1001}} and {{Text Variable B - 1002}} and {{Text Variable C- 1003}} and {{Text Variable D -1004}} and {{Text Variable E-1005}}."

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      val variables = variablesDao.findAll()
      val sectionVariableId = variables.first { it.name == "Section" }.id!!
      val textAVariableId = variables.first { it.name == "Text Variable A" }.id!!
      val textBVariableId = variables.first { it.name == "Text Variable B" }.id!!
      val textCVariableId = variables.first { it.name == "Text Variable C" }.id!!
      val textDVariableId = variables.first { it.name == "Text Variable D" }.id!!
      val textEVariableId = variables.first { it.name == "Text Variable E" }.id!!

      val expected =
          listOf(
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 1,
                  textValue = "Default text with ",
              ),
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 2,
                  usedVariableId = textAVariableId,
                  usedVariableTypeId = VariableType.Text,
                  usageTypeId = VariableUsageType.Injection,
                  displayStyleId = VariableInjectionDisplayStyle.Inline,
              ),
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 3,
                  textValue = " and ",
              ),
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 4,
                  usedVariableId = textBVariableId,
                  usedVariableTypeId = VariableType.Text,
                  usageTypeId = VariableUsageType.Injection,
                  displayStyleId = VariableInjectionDisplayStyle.Inline,
              ),
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 5,
                  textValue = " and ",
              ),
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 6,
                  usedVariableId = textCVariableId,
                  usedVariableTypeId = VariableType.Text,
                  usageTypeId = VariableUsageType.Injection,
                  displayStyleId = VariableInjectionDisplayStyle.Inline,
              ),
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 7,
                  textValue = " and ",
              ),
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 8,
                  usedVariableId = textDVariableId,
                  usedVariableTypeId = VariableType.Text,
                  usageTypeId = VariableUsageType.Injection,
                  displayStyleId = VariableInjectionDisplayStyle.Inline,
              ),
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 9,
                  textValue = " and ",
              ),
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 10,
                  usedVariableId = textEVariableId,
                  usedVariableTypeId = VariableType.Text,
                  usageTypeId = VariableUsageType.Injection,
                  displayStyleId = VariableInjectionDisplayStyle.Inline,
              ),
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 11,
                  textValue = ".",
              ),
          )

      val actual =
          variableSectionDefaultValuesDao
              .findAll()
              .sortedBy { it.listPosition }
              .map { it.copy(id = null) }

      assertEquals(expected, actual)
    }

    @Test
    fun `imports section default values with variables at the beginning`() {
      insertTextVariable(
          insertVariable(name = "Text Variable A", stableId = "1001", type = VariableType.Text)
      )

      val documentTemplateId = inserted.documentTemplateId
      val testCsv = "$header\nSection,1,,,,Yes,{{Text Variable A - 1001}} at the start."

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      val variables = variablesDao.findAll()
      val sectionVariableId = variables.first { it.name == "Section" }.id!!
      val textAVariableId = variables.first { it.name == "Text Variable A" }.id!!

      val expected =
          listOf(
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 1,
                  usedVariableId = textAVariableId,
                  usedVariableTypeId = VariableType.Text,
                  usageTypeId = VariableUsageType.Injection,
                  displayStyleId = VariableInjectionDisplayStyle.Inline,
              ),
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 2,
                  textValue = " at the start.",
              ),
          )

      val actual =
          variableSectionDefaultValuesDao
              .findAll()
              .sortedBy { it.listPosition }
              .map { it.copy(id = null) }

      assertEquals(expected, actual)
    }

    @Test
    fun `imports section default values with variables at the end`() {
      insertTextVariable(
          insertVariable(name = "Text Variable A", stableId = "1001", type = VariableType.Text)
      )

      val documentTemplateId = inserted.documentTemplateId
      val testCsv = "$header\nSection,1,,,,Yes,At the end is {{Text Variable A - 1001}}"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      val variables = variablesDao.findAll()
      val sectionVariableId = variables.first { it.name == "Section" }.id!!
      val textAVariableId = variables.first { it.name == "Text Variable A" }.id!!

      val expected =
          listOf(
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 1,
                  textValue = "At the end is ",
              ),
              VariableSectionDefaultValuesRow(
                  variableId = sectionVariableId,
                  variableTypeId = VariableType.Section,
                  variableManifestId = importResult.newVersion,
                  listPosition = 2,
                  usedVariableId = textAVariableId,
                  usedVariableTypeId = VariableType.Text,
                  usageTypeId = VariableUsageType.Injection,
                  displayStyleId = VariableInjectionDisplayStyle.Inline,
              ),
          )

      val actual =
          variableSectionDefaultValuesDao
              .findAll()
              .sortedBy { it.listPosition }
              .map { it.copy(id = null) }

      assertEquals(expected, actual)
    }

    @Test
    fun `detects nonexistent variables in section default values`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv = "$header\nSection,1,,,,Yes,At the end is {{nonexistent - 1001}}"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Variable in default section text does not exist - position: 2, referenced " +
                  "variable stable ID: 1001"
          ),
          importResult.errors,
          "Import errors",
      )
      assertTableEmpty(VARIABLE_MANIFEST_ENTRIES, "Should not have imported bad manifest")
    }

    @Test
    fun `ensures that there are no siblings with the same name`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nProject Details,1,,,,," +
              "\nSummary Description of the Project,2,,,Project Details,," +
              "\nIntroduction,3,,,Summary Description of the Project,," +
              "\nIntroduction,4,,,Summary Description of the Project,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: No two children of the same parent can have the same name, Field: Name, Value: Introduction, Position: 5"
          ),
          importResult.errors,
          "Import section variables - validate sibling names",
      )
    }

    @Test
    fun `ensures that parents exist`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nProject Details,1,,,,," +
              "\nSummary Description of the Project,2,,,Project Details,," +
              "\nIntroduction,3,,,NOT Summary Description of the Project,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Parent for variable does not exist, Field: Parent, Value: Introduction, Position: 4"
          ),
          importResult.errors,
          "parent does not exist",
      )
    }

    @Test
    fun `ensures there are no circular references`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nProject Details,1,,,Summary Description of the Project,," +
              "\nSummary Description of the Project,2,,,Project Details,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Parent for variable does not exist, Field: Parent, Value: Project Details, Position: 2",
              "Message: Parent for variable does not exist, Field: Parent, Value: Summary Description of the Project, Position: 3",
          ),
          importResult.errors,
          "parents do not exist",
      )
    }

    @Test
    fun `detects duplicate recommended variables`() {
      val testCsv = "$header\nSection Name,1,,\"Duplicate\nDuplicate\",,,"

      val importResult = importer.import(inserted.documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: The same variable is recommended more than once, Field: Recommended Variables, Value: Duplicate, Position: 2"
          ),
          importResult.errors,
      )
    }

    @Test
    fun `detects missing recommended variables`() {
      val testCsv = "$header\nSection Name,1,,Bogus Recommended,,,"

      val importResult = importer.import(inserted.documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Recommended variable does not exist - position: 2, recommended: Bogus Recommended"
          ),
          importResult.errors,
      )
    }

    @Test
    fun `detects newlines in variable names`() {
      val testCsv = "$header\n\"Section\nName\",1,,Bogus Recommended,,,"

      val importResult = importer.import(inserted.documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Name may not contain line breaks, Field: Name, Value: Section\nName, Position: 2"
          ),
          importResult.errors,
      )
    }

    @Test
    fun `detects missing names`() {
      val testCsv = header + "\n,1,,,,,"

      val importResult = importer.import(inserted.documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf("Message: Name column is required, Field: Name, Value: null, Position: 2"),
          importResult.errors,
      )
    }

    @Test
    fun `creates new child section if its parent ID has changed`() {
      val initialCsv = header + "\nTop,A,,,,," + "\nBottom,B,,,Top,,"
      val updatedCsv = header + "\nTop,X,,,,," + "\nBottom,B,,,Top,,"

      importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))
      val initialTop = getVariableByName("Top")
      val initialBottom = getVariableByName("Bottom")

      importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))
      val updatedTop = getVariableByName("Top")
      val updatedBottom = getVariableByName("Bottom")

      assertNotEquals(initialTop, updatedTop, "Top-level section should be new")
      assertNotEquals(initialBottom, updatedBottom, "Bottom section should be new")

      assertNull(updatedTop.replacesVariableId, "Replaces ID for top")
      assertEquals(initialBottom.id, updatedBottom.replacesVariableId, "Replaces ID for bottom")

      assertSetEquals(
          setOf(
              VariableSectionsRow(
                  renderHeading = true,
                  variableId = updatedTop.id,
                  variableTypeId = VariableType.Section,
              ),
              VariableSectionsRow(
                  parentVariableId = updatedTop.id,
                  parentVariableTypeId = VariableType.Section,
                  renderHeading = true,
                  variableId = updatedBottom.id,
                  variableTypeId = VariableType.Section,
              ),
          ),
          variableSectionsDao.fetchByVariableId(updatedTop.id!!, updatedBottom.id!!).toSet(),
          "Updated sections",
      )
    }

    @Test
    fun `creates new sections if a child has moved to a new parent`() {
      val initialCsv = header + "\nTop 1,A,,,,," + "\nTop 2,B,,,,," + "\nBottom,C,,,Top 1,,"
      val updatedCsv = header + "\nTop 1,A,,,,," + "\nTop 2,B,,,,," + "\nBottom,C,,,Top 2,,"

      importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))
      val initialTop1 = getVariableByName("Top 1")
      val initialTop2 = getVariableByName("Top 2")
      val initialBottom = getVariableByName("Bottom")

      importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))
      val updatedTop1 = getVariableByName("Top 1")
      val updatedTop2 = getVariableByName("Top 2")
      val updatedBottom = getVariableByName("Bottom")

      assertNotEquals(initialTop1, updatedTop1, "First top-level section should be new")
      assertNotEquals(initialTop2, updatedTop2, "Second top-level section should be new")
      assertNotEquals(initialBottom, updatedBottom, "Bottom section should be new")

      assertEquals(initialTop1.id, updatedTop1.replacesVariableId, "Replaces ID for first top")
      assertEquals(initialTop2.id, updatedTop2.replacesVariableId, "Replaces ID for second top")
      assertEquals(initialBottom.id, updatedBottom.replacesVariableId, "Replaces ID for bottom")

      assertSetEquals(
          setOf(
              VariableSectionsRow(
                  renderHeading = true,
                  variableId = updatedTop1.id,
                  variableTypeId = VariableType.Section,
              ),
              VariableSectionsRow(
                  renderHeading = true,
                  variableId = updatedTop2.id,
                  variableTypeId = VariableType.Section,
              ),
              VariableSectionsRow(
                  parentVariableId = updatedTop2.id,
                  parentVariableTypeId = VariableType.Section,
                  renderHeading = true,
                  variableId = updatedBottom.id,
                  variableTypeId = VariableType.Section,
              ),
          ),
          variableSectionsDao
              .fetchByVariableId(updatedTop1.id!!, updatedTop2.id!!, updatedBottom.id!!)
              .toSet(),
          "Updated sections",
      )
    }

    @Test
    fun `creates new top-level section if any of its descendents have changed`() {
      val initialCsv = header + "\nTop,A,,,,," + "\nMiddle,B,,,Top,," + "\nBottom,C,,,Middle,,"
      val updatedCsv = header + "\nTop,A,,,,," + "\nMiddle,B,,,Top,," + "\nBottom,C,,,Middle,yes,"

      importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))
      val initialTop = getVariableByName("Top")
      val initialMiddle = getVariableByName("Middle")
      val initialBottom = getVariableByName("Bottom")

      importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))
      val updatedTop = getVariableByName("Top")
      val updatedMiddle = getVariableByName("Middle")
      val updatedBottom = getVariableByName("Bottom")

      assertNotEquals(initialTop, updatedTop, "Top-level section should be new")
      assertNotEquals(initialMiddle, updatedMiddle, "Middle section should be new")
      assertNotEquals(initialBottom, updatedBottom, "Bottom section should be new")

      assertEquals(initialTop.id, updatedTop.replacesVariableId, "Replaces ID for top")
      assertEquals(initialMiddle.id, updatedMiddle.replacesVariableId, "Replaces ID for middle")
      assertEquals(initialBottom.id, updatedBottom.replacesVariableId, "Replaces ID for bottom")

      assertSetEquals(
          setOf(
              VariableSectionsRow(
                  renderHeading = true,
                  variableId = updatedTop.id,
                  variableTypeId = VariableType.Section,
              ),
              VariableSectionsRow(
                  parentVariableId = updatedTop.id,
                  parentVariableTypeId = VariableType.Section,
                  renderHeading = true,
                  variableId = updatedMiddle.id,
                  variableTypeId = VariableType.Section,
              ),
              VariableSectionsRow(
                  parentVariableId = updatedMiddle.id,
                  parentVariableTypeId = VariableType.Section,
                  renderHeading = false,
                  variableId = updatedBottom.id,
                  variableTypeId = VariableType.Section,
              ),
          ),
          variableSectionsDao
              .fetchByVariableId(updatedTop.id!!, updatedMiddle.id!!, updatedBottom.id!!)
              .toSet(),
          "Updated sections",
      )
    }

    @Test
    fun `creates new top-level section if new level is added to hierarchy`() {
      val initialCsv =
          header + //
              "\nTop,A,,,,," +
              "\nMiddle,B,,,Top,,"
      val updatedCsv = header + "\nTop,A,,,,," + "\nMiddle,B,,,Top,," + "\nBottom,C,,,Middle,,"

      importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))
      val initialTop = getVariableByName("Top")
      val initialMiddle = getVariableByName("Middle")

      importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))
      val updatedTop = getVariableByName("Top")
      val updatedMiddle = getVariableByName("Middle")
      val updatedBottom = getVariableByName("Bottom")

      assertNotEquals(initialTop, updatedTop, "Top-level section should be new")
      assertNotEquals(initialMiddle, updatedMiddle, "Middle section should be new")

      assertEquals(initialTop.id, updatedTop.replacesVariableId, "Replaces ID for top")
      assertEquals(initialMiddle.id, updatedMiddle.replacesVariableId, "Replaces ID for middle")

      assertSetEquals(
          setOf(
              VariableSectionsRow(
                  renderHeading = true,
                  variableId = updatedTop.id,
                  variableTypeId = VariableType.Section,
              ),
              VariableSectionsRow(
                  parentVariableId = updatedTop.id,
                  parentVariableTypeId = VariableType.Section,
                  renderHeading = true,
                  variableId = updatedMiddle.id,
                  variableTypeId = VariableType.Section,
              ),
              VariableSectionsRow(
                  parentVariableId = updatedMiddle.id,
                  parentVariableTypeId = VariableType.Section,
                  renderHeading = true,
                  variableId = updatedBottom.id,
                  variableTypeId = VariableType.Section,
              ),
          ),
          variableSectionsDao
              .fetchByVariableId(updatedTop.id!!, updatedMiddle.id!!, updatedBottom.id!!)
              .toSet(),
          "Updated sections",
      )
    }

    @Test
    fun `reuses section if none of its descendents have changed`() {
      val initialCsv = header + "\nInitial Top,A,,,,," + "\nInitial Bottom,B,,,Initial Top,,"
      val updatedCsv = header + "\nInitial Top,A,,,,," + "\nInitial Bottom,B,,,Initial Top,,"

      importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))
      val initialTop = getVariableByName("Initial Top")
      val initialBottom = getVariableByName("Initial Bottom")

      importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))
      val updatedTop = getVariableByName("Initial Top")
      val updatedBottom = getVariableByName("Initial Bottom")

      assertEquals(initialTop, updatedTop, "Top-level section should be reused")
      assertEquals(initialBottom, updatedBottom, "Top-level section should be reused")
    }

    @Test
    fun `populates default value for new manifest if section has not changed`() {
      val csv = header + "\nSection,A,,,,,Default Value"

      val initialManifestId =
          importer.import(inserted.documentTemplateId, sizedInputStream(csv)).newVersion
      val updatedManifestId =
          importer.import(inserted.documentTemplateId, sizedInputStream(csv)).newVersion
      val variableId = getVariableByName("Section").id

      assertTableEquals(
          listOf(
              VariableSectionDefaultValuesRecord(
                  listPosition = 1,
                  textValue = "Default Value",
                  variableId = variableId,
                  variableManifestId = initialManifestId,
                  variableTypeId = VariableType.Section,
              ),
              VariableSectionDefaultValuesRecord(
                  listPosition = 1,
                  textValue = "Default Value",
                  variableId = variableId,
                  variableManifestId = updatedManifestId,
                  variableTypeId = VariableType.Section,
              ),
          )
      )
    }

    private fun sizedInputStream(content: ByteArray) =
        SizedInputStream(content.inputStream(), content.size.toLong())

    private fun sizedInputStream(content: String) = sizedInputStream(content.toByteArray())

    // This is not safe to use in tests where there are multiple variables with the same name, this
    // does not care about hierarchy and will grab the first one that matches by name in reversed
    // order
    private fun getVariableByName(name: String): VariablesRow =
        variablesDao.fetchByName(name).sortedBy { it.id }.reversed().first()
  }
}

private data class FlatVariable(
    val variableId: VariableId,
    val variableName: String?,
    val parentId: VariableId?,
    val parentVariableName: String?,
)
