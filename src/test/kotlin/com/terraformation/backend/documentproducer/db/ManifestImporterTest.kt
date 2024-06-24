package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableTableStyle
import com.terraformation.backend.db.docprod.VariableTextType
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.tables.pojos.VariableManifestEntriesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableNumbersRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionRecommendationsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectOptionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTableColumnsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTablesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTextsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariablesRow
import com.terraformation.backend.documentproducer.db.manifest.ManifestImporter
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder

class ManifestImporterTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val messages = Messages()
  private val clock = TestClock()

  private val variableManifestStore: VariableManifestStore by lazy {
    VariableManifestStore(
        clock, documentTemplatesDao, dslContext, variableManifestsDao, variableManifestEntriesDao)
  }

  private val variableStore: VariableStore by lazy {
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

  private val importer: ManifestImporter by lazy {
    ManifestImporter(dslContext, messages, variableManifestStore, variableStore)
  }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertDocumentTemplate()
  }

  @Nested
  inner class UploadManifest {
    private var oldAuthentication: Authentication? = null

    private val header =
        "Name,ID,Description,Data Type,List?,Recommended variables,Parent,Non-numbered section?,Options,Minimum value,Maximum value,Decimal places,Table Style,Header,Notes"

    @BeforeEach
    fun setUp() {
      every { user.canCreateVariableManifest() } returns true
    }

    @AfterEach
    fun tearDown() {
      SecurityContextHolder.getContext().authentication = oldAuthentication
    }

    @Test
    fun `detects duplicate stable IDs`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nName X,Duplicate ID,,Section,Yes,,,,,,,,,," +
              "\nName Y,Duplicate ID,,Section,Yes,,,,,,,,,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: ID must be unique across the entire manifest, Field: ID, Value: Duplicate ID, Position: 3"),
          importResult.errors)
    }

    @Test
    fun `correctly identifies when there is a non-unique top level input name`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nProject Details,1,,Section,Yes,,,,,,,,,," +
              "\nProject Details,2,,Section,Yes,,,,,,,,,," +
              "\nSummary Description of the Project,3,,Section,Yes,,Project Details,,,,,,,," +
              "\nIntroduction,4,,Section,Yes,,Summary Description of the Project,,,,,,,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Top Level Variable Name must be unique, Field: Name, Value: Project Details, Position: 3"),
          importResult.errors,
          "Non-Unique Input Names")
    }

    @Test
    fun `imports table variables correctly`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nProject Proponent Table,1,A table with contact details,Table,No,,,,,,,,,," +
              "\nOrganization Name,2,,Text (single-line),,,Project Proponent Table,,,,,,,," +
              "\nContact Person,3,,Text (single-line),,,Project Proponent Table,,,,,,,," +
              "\nTitle,4,,Text (single-line),,,Project Proponent Table,,,,,,,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      val actualTableVariable = getVariableByName("Project Proponent Table")
      val expectedTableVariable =
          VariablesRow(
              description = "A table with contact details",
              id = actualTableVariable.id!!,
              internalOnly = false,
              isList = false,
              name = "Project Proponent Table",
              stableId = "1",
              variableTypeId = VariableType.Table)

      val actualTableRow = variableTablesDao.fetchOneByVariableId(actualTableVariable.id!!)
      val expectedTableRow =
          VariableTablesRow(
              variableId = actualTableVariable.id!!,
              variableTypeId = VariableType.Table,
              tableStyleId = VariableTableStyle.Horizontal)

      val tableColumnVariableRow1 = getVariableByName("Organization Name")
      val tableColumnVariableRow2 = getVariableByName("Contact Person")
      val tableColumnVariableRow3 = getVariableByName("Title")

      val actualTableColumnRows = variableTableColumnsDao.findAll()
      val expectedTableColumnRows =
          listOf(
              VariableTableColumnsRow(
                  variableId = tableColumnVariableRow1.id!!,
                  tableVariableId = actualTableVariable.id!!,
                  tableVariableTypeId = VariableType.Table,
                  position = 1,
                  isHeader = false),
              VariableTableColumnsRow(
                  variableId = tableColumnVariableRow2.id!!,
                  tableVariableId = actualTableVariable.id!!,
                  tableVariableTypeId = VariableType.Table,
                  position = 2,
                  isHeader = false),
              VariableTableColumnsRow(
                  variableId = tableColumnVariableRow3.id!!,
                  tableVariableId = actualTableVariable.id!!,
                  tableVariableTypeId = VariableType.Table,
                  position = 3,
                  isHeader = false))

      assertEquals(emptyList<String>(), importResult.errors, "no errors")
      assertEquals(
          expectedTableVariable, actualTableVariable, "Variable DB row for table is correct")
      assertEquals(expectedTableRow, actualTableRow, "Variable Table DB row for table is correct")
      assertEquals(
          expectedTableColumnRows,
          actualTableColumnRows,
          "Variable Table Column DB rows are correct")
    }

    @Test
    fun `imports complex table with select option header column correctly`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nAudit history,1,,Table,Yes,,,,,,,,Horizontal,," +
              "\nAudit type,2,,Select (single),,,Audit history,,\"- Validation/verification" +
              "\n- Some other audit type\",,,,,Yes," +
              "\nNumber of years,3,,Number,,,Audit history,,,,,1,,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      val actualTableVariable = getVariableByName("Audit history")

      val tableColumnVariableRow1 = getVariableByName("Audit type")
      val tableColumnVariableRow2 = getVariableByName("Number of years")

      val actualTableColumnRows = variableTableColumnsDao.findAll()
      val expectedTableColumnRows =
          listOf(
              VariableTableColumnsRow(
                  variableId = tableColumnVariableRow1.id!!,
                  tableVariableId = actualTableVariable.id!!,
                  tableVariableTypeId = VariableType.Table,
                  position = 1,
                  isHeader = true),
              VariableTableColumnsRow(
                  variableId = tableColumnVariableRow2.id!!,
                  tableVariableId = actualTableVariable.id!!,
                  tableVariableTypeId = VariableType.Table,
                  position = 2,
                  isHeader = false))

      val actualNumberRow = variableNumbersDao.fetchOneByVariableId(tableColumnVariableRow2.id!!)
      val expectedNumberRow =
          VariableNumbersRow(
              variableId = tableColumnVariableRow2.id!!,
              variableTypeId = VariableType.Number,
              decimalPlaces = 1)

      val actualSelectRow = variableSelectsDao.fetchOneByVariableId(tableColumnVariableRow1.id!!)
      val expectedSelectRow =
          VariableSelectsRow(
              variableId = tableColumnVariableRow1.id!!,
              variableTypeId = VariableType.Select,
              isMultiple = false)

      val actualSelectOptionRows =
          variableSelectOptionsDao.fetchByVariableId(tableColumnVariableRow1.id!!)
      val expectedSelectOptionRows =
          listOf(
              VariableSelectOptionsRow(
                  variableId = tableColumnVariableRow1.id!!,
                  variableTypeId = VariableType.Select,
                  name = "Validation/verification",
                  position = 1),
              VariableSelectOptionsRow(
                  variableId = tableColumnVariableRow1.id!!,
                  variableTypeId = VariableType.Select,
                  name = "Some other audit type",
                  position = 2))

      assertEquals(emptyList<String>(), importResult.errors, "no errors")
      assertEquals(
          expectedTableColumnRows,
          actualTableColumnRows,
          "Variable Table Column DB rows are correct")
      assertEquals(expectedNumberRow, actualNumberRow, "Variable Number row is correct")
      assertEquals(expectedSelectRow, actualSelectRow, "Variable Select row is correct")
      assertEquals(
          expectedSelectOptionRows,
          actualSelectOptionRows.map { it.copy(id = null) },
          "Variable Select Option rows are correct")
    }

    @Test
    fun `imports section variables correctly`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nProject Details,1,,Section,Yes,,,,,,,,,," +
              "\nSummary Description of the Project,2,,Section,Yes,,Project Details,,,,,,,," +
              "\nIntroduction ,3,,Section,Yes,,Summary Description of the Project,,,,,,,," +
              "\nBrief description of the project,4,,Section,Yes,, Introduction,yes,,,,,,," +
              "\nCurrent status of the project and the area it covers.,5,,Section,Yes,,Introduction,yes,,,,,,," +
              "\nProject Details,6,,Section,Yes,,Summary Description of the Project,,,,,,,," +
              "\nDetailed explanation,7,,Section,Yes,,Project Details,yes,,,,,,," +
              "\nInformation about the land,8,,Section,Yes,,Project Details,yes,,,,,,," +
              "\nProject Management,9,,Section,Yes,,Summary Description of the Project,,,,,,,,"

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
                    })
          }

      fun getSectionVariableByNameAndParent(
          name: String,
          parent: String? = null
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
              renderHeading = true)

      val actualLevel3SectionVariable =
          getSectionVariableByNameAndParent("Project Details", "Summary Description of the Project")
      val expectedLevel3SectionVariable =
          VariableSectionsRow(
              variableTypeId = VariableType.Section,
              parentVariableId = actualLevel2SectionVariable!!.variableId,
              parentVariableTypeId = VariableType.Section,
              renderHeading = true)

      // This is a non-numbered section, there should be no header rendering
      val actualLevel4SectionVariable =
          getSectionVariableByNameAndParent("Information about the land", "Project Details")
      val expectedLevel4SectionVariable =
          VariableSectionsRow(
              variableTypeId = VariableType.Section,
              parentVariableId = actualLevel3SectionVariable!!.variableId,
              parentVariableTypeId = VariableType.Section,
              renderHeading = false)

      assertEquals(emptyList<String>(), importResult.errors, "no errors")

      // Ignore the variable IDs in the actual variables because they are being validated implicitly
      // through the child/parent relationship
      assertEquals(
          expectedLevel1SectionVariable,
          actualLevel1SectionVariable.copy(variableId = null),
          "level 1 section variable")
      assertEquals(
          expectedLevel2SectionVariable,
          actualLevel2SectionVariable.copy(variableId = null),
          "level 2 section variable")
      assertEquals(
          expectedLevel3SectionVariable,
          actualLevel3SectionVariable.copy(variableId = null),
          "level 3 section variable")
      assertEquals(
          expectedLevel4SectionVariable,
          actualLevel4SectionVariable?.copy(variableId = null),
          "level 4 section variable")
    }

    @Test
    fun `ensures that there are no siblings with the same name`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nProject Details,1,,Section,Yes,,,,,,,,,," +
              "\nSummary Description of the Project,2,,Section,Yes,,Project Details,,,,,,,," +
              "\nIntroduction,3,,Section,Yes,,Summary Description of the Project,,,,,,,," +
              "\nIntroduction,4,,Section,Yes,,Summary Description of the Project,,,,,,,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: No two children of the same parent can have the same name, Field: Name, Value: Introduction, Position: 5"),
          importResult.errors,
          "Import section variables - validate sibling names")
    }

    @Test
    fun `ensures that parents exist`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nProject Details,1,,Section,Yes,,,,,,,,,," +
              "\nSummary Description of the Project,2,,Section,Yes,,Project Details,,,,,,,," +
              "\nIntroduction,3,,Section,Yes,,NOT Summary Description of the Project,,,,,,,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Parent for variable does not exist, Field: Parent, Value: Introduction, Position: 4"),
          importResult.errors,
          "parent does not exist")
    }

    @Test
    fun `ensures there are no circular references`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nProject Details,1,,Section,Yes,,Summary Description of the Project,,,,,,,," +
              "\nSummary Description of the Project,2,,Section,Yes,,Project Details,,,,,,,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Parent for variable does not exist, Field: Parent, Value: Project Details, Position: 2",
              "Message: Parent for variable does not exist, Field: Parent, Value: Summary Description of the Project, Position: 3"),
          importResult.errors,
          "parents do not exist")
    }

    @Test
    fun `ensures that single and multi line text are saved into the DB as expected`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nOrganization Name,1,,Text (single-line),,,,,,,,,,," +
              "\nOrganization Name As List,2,,Text (single-line),yes,,,,,,,,,," +
              "\nPrior Scenario,3,A brief description of the scenario,Text (multi-line),,,,,,,,,,," +
              "\nPrior Scenario As List,4,A brief description of the scenario,Text (multi-line),yes,,,,,,,,,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      val actualVariableO = getVariableByName("Organization Name")
      val actualVariableTextO = variableTextsDao.fetchOneByVariableId(actualVariableO.id!!)
      val actualVariableOAL = getVariableByName("Organization Name As List")
      val actualVariableTextOAL = variableTextsDao.fetchOneByVariableId(actualVariableOAL.id!!)
      val actualVariablePS = getVariableByName("Prior Scenario")
      val actualVariableTextPS = variableTextsDao.fetchOneByVariableId(actualVariablePS.id!!)
      val actualVariablePSAL = getVariableByName("Prior Scenario As List")
      val actualVariableTextPSAL = variableTextsDao.fetchOneByVariableId(actualVariablePSAL.id!!)

      assertEquals(emptyList<String>(), importResult.errors, "no errors")

      assertFalse(actualVariableO.isList!!, "single input without list is not a list")
      assertEquals(
          VariableTextsRow(
              variableId = actualVariableO.id!!,
              variableTypeId = VariableType.Text,
              variableTextTypeId = VariableTextType.SingleLine),
          actualVariableTextO,
          "single line text variable saved correctly")

      assertTrue(actualVariableOAL.isList!!, "single input with list is a list")
      assertEquals(
          VariableTextsRow(
              variableId = actualVariableOAL.id!!,
              variableTypeId = VariableType.Text,
              variableTextTypeId = VariableTextType.SingleLine),
          actualVariableTextOAL,
          "single line text variable saved correctly")

      assertFalse(actualVariablePS.isList!!, "multi input without list is not a list")
      assertEquals(
          VariableTextsRow(
              variableId = actualVariablePS.id!!,
              variableTypeId = VariableType.Text,
              variableTextTypeId = VariableTextType.MultiLine),
          actualVariableTextPS,
          "multi line text variable saved correctly")

      assertTrue(actualVariablePSAL.isList!!, "multi input with list is a list")
      assertEquals(
          VariableTextsRow(
              variableId = actualVariablePSAL.id!!,
              variableTypeId = VariableType.Text,
              variableTextTypeId = VariableTextType.MultiLine),
          actualVariableTextPSAL,
          "multi line text variable saved correctly")
    }

    @Test
    fun `ensures that single and multi selects are saved into the DB as expected`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              // Single select
              "\nEstimated reductions,1,Canned description,Select (single),,,,,\"- tiny" +
              "\n- small" +
              "\n- medium" +
              "\n- large\",,,,,," +
              // Multi select
              "\nMade up multi select,2,Select as many as you want!,Select (multiple),,,,,\"- Option 1" +
              "\n- Option 2" +
              "\n- Option 3" +
              "\n- Option 4 [[This one has super special rendered text!]]" +
              "\n- Option 5\",,,,,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      val selectRows = variableSelectsDao.findAll()
      val selectOptionRows = variableSelectOptionsDao.findAll()

      val variableEAGER = getVariableByName("Estimated reductions")
      val variableSelectEAGER = selectRows.find { it.variableId == variableEAGER.id }
      val variableSelectOptionsEAGER = selectOptionRows.filter { it.variableId == variableEAGER.id }
      // Scrutinize a specific option we expect
      val variableSelectOption3EAGER =
          variableSelectOptionsEAGER.find { it.name!!.endsWith("medium") }

      val variableMUMS = getVariableByName("Made up multi select")
      val variableSelectMUMS = selectRows.find { it.variableId == variableMUMS.id }
      val variableSelectOptionsMUMS = selectOptionRows.filter { it.variableId == variableMUMS.id }
      // Scrutinize a specific option we expect
      val variableSelectOption4MUMS =
          variableSelectOptionsMUMS.find { it.name!!.endsWith("Option 4") }

      assertEquals(emptyList<String>(), importResult.errors, "no errors")
      assertFalse(variableEAGER.isList!!, "single select is not a list")
      assertNotNull(variableSelectEAGER, "single select variable select was created")
      assertFalse(
          variableSelectEAGER?.isMultiple!!, "single select variable select is not multiple")
      assertEquals(
          variableSelectOptionsEAGER.size, 4, "single select variable select options were created")

      assertEquals(
          VariableSelectOptionsRow(
              name = "medium",
              position = 3,
              variableId = variableEAGER.id,
              variableTypeId = variableEAGER.variableTypeId),
          variableSelectOption3EAGER?.copy(id = null),
          "single select option")

      assertFalse(variableMUMS.isList!!, "multi select is not a list")
      assertNotNull(variableSelectMUMS, "multi select variable select was created")
      assertTrue(variableSelectMUMS?.isMultiple!!, "multi select variable select is multiple")
      assertEquals(
          variableSelectOptionsMUMS.size, 5, "multi select variable select options were created")

      assertEquals(
          VariableSelectOptionsRow(
              name = "Option 4",
              position = 4,
              renderedText = "This one has super special rendered text!",
              variableId = variableMUMS.id,
              variableTypeId = variableMUMS.variableTypeId),
          variableSelectOption4MUMS?.copy(id = null),
          "multi select option with rendered text")
    }

    @Test
    fun `ensures that select options are unique within the select`() {
      val documentTemplateId = inserted.documentTemplateId
      val testCsv =
          header +
              "\nEstimated reductions,1,Canned Description,Select (single),,,,,\"- tiny amount" +
              "\n- small amount" +
              "\n- medium amount" +
              "\n- medium amount" +
              "\n- large amount\",,,,,,"

      val importResult = importer.import(documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Select option values must be unique within the select variable, Field: Options, Value: - tiny amount" +
                  "\n- small amount" +
                  "\n- medium amount" +
                  "\n- medium amount" +
                  "\n- large amount, Position: 2"),
          importResult.errors,
          "duplicate option error")
    }

    @Test
    fun `detects duplicate recommended variables`() {
      val testCsv = "$header\nSection Name,1,,Section,,\"Duplicate\nDuplicate\",,,,,,,,,"

      val importResult = importer.import(inserted.documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: The same variable is recommended more than once, Field: Recommended Variables, Value: Duplicate, Position: 2"),
          importResult.errors)
    }

    @Test
    fun `detects missing recommended variables`() {
      val testCsv = "$header\nSection Name,1,,Section,,Bogus Recommended,,,,,,,,,"

      val importResult = importer.import(inserted.documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Recommended variable does not exist - position: 2, recommended: Bogus Recommended"),
          importResult.errors)
    }

    @Test
    fun `detects newlines in variable names`() {
      val testCsv = "$header\n\"Section\nName\",1,,Section,,Bogus Recommended,,,,,,,,,"

      val importResult = importer.import(inserted.documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Name may not contain line breaks, Field: Name, Value: Section\nName, Position: 2"),
          importResult.errors)
    }

    @Test
    fun `detects sections with tables as parents`() {
      val testCsv =
          header +
              "\nMy Table,1,,Table,Yes,,,,,,,,,," +
              "\nMy Section,2,,Section,,,My Table,,,,,,,,"

      val importResult = importer.import(inserted.documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Sections can only be children of other sections, not of other variable " +
                  "types, Field: Parent, Value: My Table, Position: 3"),
          importResult.errors)
    }

    @Test
    fun `detects non-sections with sections as parents`() {
      val testCsv =
          header + "\nMy Section,1,,Section,Yes,,,,,,,,,," + "\nOther,2,,Table,,,My Section,,,,,,,,"

      val importResult = importer.import(inserted.documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Only tables and sections may be listed as parents, Field: Parent, Value: " +
                  "My Section, Position: 3"),
          importResult.errors)
    }

    @Test
    fun `detects missing names`() {
      val testCsv = header + "\n,1,,Section,Yes,,,,,,,,,,"

      val importResult = importer.import(inserted.documentTemplateId, sizedInputStream(testCsv))

      assertEquals(
          listOf("Message: Name column is required, Field: Name, Value: null, Position: 2"),
          importResult.errors)
    }

    @Test
    fun `ensures that the current CSV imports without error`() {
      val documentTemplateId = inserted.documentTemplateId
      val csvInput = javaClass.getResourceAsStream("/manifest/variable-manifest-rev5.csv")!!

      val importResult = importer.import(documentTemplateId, csvInput)

      assertEquals(emptyList<String>(), importResult.errors, "no errors")
    }

    @Test
    fun `reuses existing select variable`() {
      val testCsv =
          header +
              "\nSelect Variable,A,,Select (single),,,,,\"- Option 1\n- Option 2\n- Option 3\",,,,,,"

      val initialResult = importer.import(inserted.documentTemplateId, sizedInputStream(testCsv))
      val updatedResult = importer.import(inserted.documentTemplateId, sizedInputStream(testCsv))

      val variablesRows = variablesDao.findAll()
      assertEquals(1, variablesRows.size, "Number of imported variables")

      assertEquals(
          setOf(
              VariableManifestEntriesRow(
                  position = 2,
                  variableId = variablesRows[0].id,
                  variableManifestId = initialResult.newVersion,
              ),
              VariableManifestEntriesRow(
                  position = 2,
                  variableId = variablesRows[0].id,
                  variableManifestId = updatedResult.newVersion,
              ),
          ),
          variableManifestEntriesDao.findAll().toSet(),
          "Should have inserted a manifest entry for each manifest")
    }

    @Test
    fun `creates new select variable if options have changed`() {
      val initialCsv =
          "$header\nSelect,1,,Select (single),,,,,\"- Option 1\n- Option 2\n- Option 3\",,,,,,"
      val updatedCsv = "$header\nSelect,1,,Select (single),,,,,\"- Option 1\n- Option 2\",,,,,,"

      val initialResult = importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))
      val updatedResult = importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))

      val variablesRows = variablesDao.findAll().sortedBy { it.id!!.value }
      assertEquals(2, variablesRows.size, "Number of imported variables")

      assertEquals(
          setOf(
              VariableManifestEntriesRow(
                  position = 2,
                  variableId = variablesRows[0].id,
                  variableManifestId = initialResult.newVersion,
              ),
              VariableManifestEntriesRow(
                  position = 2,
                  variableId = variablesRows[1].id,
                  variableManifestId = updatedResult.newVersion,
              ),
          ),
          variableManifestEntriesDao.findAll().toSet(),
          "Should have inserted a manifest entry for each manifest")

      assertEquals(
          setOf("Option 1", "Option 2", "Option 3"),
          variableSelectOptionsDao.fetchByVariableId(variablesRows[0].id!!).map { it.name }.toSet(),
          "Initial options")
      assertEquals(
          setOf("Option 1", "Option 2"),
          variableSelectOptionsDao.fetchByVariableId(variablesRows[1].id!!).map { it.name }.toSet(),
          "Updated options")
    }

    @Test
    fun `creates new top-level section if any of its descendents have changed`() {
      val initialCsv =
          header +
              "\nTop,A,,Section,,,,,,,,,,," +
              "\nMiddle,B,,Section,,,Top,,,,,,,," +
              "\nBottom,C,,Section,,,Middle,,,,,,,,"
      val updatedCsv =
          header +
              "\nTop,A,,Section,,,,,,,,,,," +
              "\nMiddle,B,,Section,,,Top,,,,,,,," +
              "\nBottom,C,,Section,,,Middle,yes,,,,,,,"

      importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))
      val initialTop = getVariableByName("Top")
      val initialMiddle = getVariableByName("Middle")
      val initialBottom = getVariableByName("Bottom")

      val updatedResult = importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))
      val updatedTop = getVariableByName("Top")
      val updatedMiddle = getVariableByName("Middle")
      val updatedBottom = getVariableByName("Bottom")

      assertNotEquals(initialTop, updatedTop, "Top-level section should be new")
      assertNotEquals(initialMiddle, updatedMiddle, "Middle section should be new")
      assertNotEquals(initialBottom, updatedBottom, "Bottom section should be new")

      assertEquals(initialTop.id, updatedTop.replacesVariableId, "Replaces ID for top")
      assertEquals(initialMiddle.id, updatedMiddle.replacesVariableId, "Replaces ID for middle")
      assertEquals(initialBottom.id, updatedBottom.replacesVariableId, "Replaces ID for bottom")

      assertEquals(
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
          "Updated sections")
    }

    @Test
    fun `creates new top-level section if new level is added to hierarchy`() {
      val initialCsv =
          header + //
              "\nTop,A,,Section,,,,,,,,,,," +
              "\nMiddle,B,,Section,,,Top,,,,,,,,"
      val updatedCsv =
          header +
              "\nTop,A,,Section,,,,,,,,,,," +
              "\nMiddle,B,,Section,,,Top,,,,,,,," +
              "\nBottom,C,,Section,,,Middle,,,,,,,,"

      importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))
      val initialTop = getVariableByName("Top")
      val initialMiddle = getVariableByName("Middle")

      val updatedResult = importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))
      val updatedTop = getVariableByName("Top")
      val updatedMiddle = getVariableByName("Middle")
      val updatedBottom = getVariableByName("Bottom")

      assertNotEquals(initialTop, updatedTop, "Top-level section should be new")
      assertNotEquals(initialMiddle, updatedMiddle, "Middle section should be new")

      assertEquals(initialTop.id, updatedTop.replacesVariableId, "Replaces ID for top")
      assertEquals(initialMiddle.id, updatedMiddle.replacesVariableId, "Replaces ID for middle")

      assertEquals(
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
          "Updated sections")
    }

    @Test
    fun `reuses section if none of its descendents have changed`() {
      val initialCsv =
          header +
              "\nInitial Top,A,,Section,,,,,,,,,,," +
              "\nInitial Bottom,B,,Section,,,Initial Top,,,,,,,,"
      val updatedCsv =
          header +
              "\nInitial Top,A,,Section,,,,,,,,,,," +
              "\nInitial Bottom,B,,Section,,,Initial Top,,,,,,,,"

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
    fun `uses recommended variables list from new manifest if a section variable is reused`() {
      val initialCsv =
          header +
              "\nVariable 1,A,,Text (single-line),,,,,,,,,,," +
              "\nVariable 2,B,,Text (single-line),,,,,,,,,,," +
              "\nSection 1,C,,Section,,Variable 1,,,,,,,,," +
              "\nSection 2,D,,Section,,Variable 2,,,,,,,,,"
      val updatedCsv =
          header +
              "\nVariable 1,A,,Text (single-line),,,,,,,,,,," +
              "\nVariable 2,B,,Text (single-line),,,,,,,,,,," +
              "\nSection 1,C,,Section,,Variable 2,,,,,,,,," +
              "\nSection 2,D,,Section,,Variable 2,,,,,,,,,"

      val initialResult = importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))
      val initialManifestId = initialResult.newVersion
      val updatedResult = importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))

      val variablesRows = variablesDao.findAll()
      assertEquals(4, variablesRows.size, "Number of variables imported")

      val section1Row = getVariableByName("Section 1")
      val section2Row = getVariableByName("Section 2")
      val variable1Row = getVariableByName("Variable 1")
      val variable2Row = getVariableByName("Variable 2")

      assertEquals(
          setOf(
              VariableSectionRecommendationsRow(
                  recommendedVariableId = variable1Row.id!!,
                  sectionVariableId = section1Row.id!!,
                  sectionVariableTypeId = VariableType.Section,
                  variableManifestId = initialManifestId,
              ),
              VariableSectionRecommendationsRow(
                  recommendedVariableId = variable2Row.id!!,
                  sectionVariableId = section1Row.id!!,
                  sectionVariableTypeId = VariableType.Section,
                  variableManifestId = updatedResult.newVersion,
              ),
              VariableSectionRecommendationsRow(
                  recommendedVariableId = variable2Row.id!!,
                  sectionVariableId = section2Row.id!!,
                  sectionVariableTypeId = VariableType.Section,
                  variableManifestId = initialManifestId,
              ),
              VariableSectionRecommendationsRow(
                  recommendedVariableId = variable2Row.id!!,
                  sectionVariableId = section2Row.id!!,
                  sectionVariableTypeId = VariableType.Section,
                  variableManifestId = updatedResult.newVersion,
              ),
          ),
          variableSectionRecommendationsDao.findAll().toSet(),
          "Recommended variables")
    }

    @Test
    fun `creates new variable if name or description have changed`() {
      val initialCsv =
          "$header\nOriginal variable,1,Original description,Text (single-line),,,,,,,,,,,"
      val updatedCsv =
          "$header\nUpdated variable,1,Updated description,Text (single-line),,,,,,,,,,,"

      val initialResult = importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))

      val initialVariables = variablesDao.findAll()
      assertEquals(1, initialVariables.size, "Should have imported 1 variable")
      val initialVariableId = initialVariables.first().id!!

      val updateResult = importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))

      val updatedVariables = variablesDao.findAll()
      assertEquals(2, updatedVariables.size, "Should have imported 1 new variable")
      val updatedVariableId = updatedVariables.find { it.id != initialVariableId }!!.id

      assertEquals(
          setOf(
              VariableManifestEntriesRow(
                  position = 2,
                  variableId = initialVariableId,
                  variableManifestId = initialResult.newVersion,
              ),
              VariableManifestEntriesRow(
                  position = 2,
                  variableId = updatedVariableId,
                  variableManifestId = updateResult.newVersion,
              ),
          ),
          variableManifestEntriesDao.findAll().toSet(),
          "Both versions of the variable are present as manifest entries")

      assertEquals(
          setOf(
              VariablesRow(
                  description = "Original description",
                  id = initialVariableId,
                  internalOnly = false,
                  isList = false,
                  name = "Original variable",
                  stableId = "1",
                  variableTypeId = VariableType.Text,
              ),
              VariablesRow(
                  description = "Updated description",
                  id = updatedVariableId,
                  internalOnly = false,
                  isList = false,
                  name = "Updated variable",
                  replacesVariableId = initialVariableId,
                  stableId = "1",
                  variableTypeId = VariableType.Text,
              ),
          ),
          variablesDao.findAll().toSet(),
          "New version of the variable should be present")
    }

    @Test
    fun `creates new variable if validation settings have changed`() {
      val initialCsv = "$header\nNumber variable,1,,Number,,,,,,10,,,,,"
      val updatedCsv = "$header\nNumber variable,1,,Number,,,,,,20,,,,,"

      importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))

      val initialVariables = variablesDao.findAll()
      assertEquals(1, initialVariables.size, "Should have imported 1 variable")
      val initialVariableId = initialVariables.first().id!!

      val updateResult = importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))

      val updatedVariables = variablesDao.findAll()
      assertEquals(2, updatedVariables.size, "Should have imported new copy of variable")
      val newVariableId = updatedVariables.map { it.id!! }.first { it != initialVariableId }

      assertEquals(
          initialVariableId,
          updatedVariables.first { it.id == newVariableId }.replacesVariableId,
          "New variable should be marked as replacement of existing one")

      assertEquals(
          VariableManifestEntriesRow(
              position = 2,
              variableId = newVariableId,
              variableManifestId = updateResult.newVersion,
          ),
          variableManifestEntriesDao.fetchByVariableManifestId(updateResult.newVersion!!).single(),
          "New manifest should use new variable")

      assertEquals(
          setOf(
              VariablesRow(
                  id = initialVariableId,
                  internalOnly = false,
                  isList = false,
                  name = "Number variable",
                  stableId = "1",
                  variableTypeId = VariableType.Number,
              ),
              VariablesRow(
                  id = newVariableId,
                  internalOnly = false,
                  isList = false,
                  name = "Number variable",
                  replacesVariableId = initialVariableId,
                  stableId = "1",
                  variableTypeId = VariableType.Number,
              )),
          variablesDao.findAll().toSet(),
          "Both versions of the variable are present")
    }

    @Test
    fun `Creates new table and column variables if the column names are updated`() {
      val initialCsv =
          header +
              "\nTable,1,,Table,Yes,,,,,,,,,," +
              "\nColumn A,2,,Number,,,Table,,,,,,,," +
              "\nColumn B,3,,Number,,,Table,,,,,,,,"
      val updatedCsv =
          header +
              "\nTable,1,,Table,Yes,,,,,,,,,," +
              "\nRenamed A,2,,Number,,,Table,,,,,,,," +
              "\nRenamed B,3,,Number,,,Table,,,,,,,,"

      importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))

      val initialVariables = variablesDao.findAll().sortedBy { it.id!!.value }
      assertEquals(3, initialVariables.size, "Should have imported 3 variables")

      val updateResult = importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))

      val updatedVariables = variablesDao.findAll().sortedBy { it.id!!.value }
      assertEquals(6, updatedVariables.size, "Should have created 2 new variables")

      assertEquals(
          setOf(
              VariableManifestEntriesRow(
                  position = 2,
                  variableId = updatedVariables[3].id,
                  variableManifestId = updateResult.newVersion,
              ),
              VariableManifestEntriesRow(
                  position = 3,
                  variableId = updatedVariables[4].id,
                  variableManifestId = updateResult.newVersion,
              ),
              VariableManifestEntriesRow(
                  position = 4,
                  variableId = updatedVariables[5].id,
                  variableManifestId = updateResult.newVersion,
              ),
          ),
          variableManifestEntriesDao.fetchByVariableManifestId(updateResult.newVersion!!).toSet(),
          "New manifest should use existing variable for table and new variables for renamed columns")

      assertEquals(
          setOf(
              VariablesRow(
                  id = initialVariables[0].id,
                  internalOnly = false,
                  isList = true,
                  name = "Table",
                  stableId = "1",
                  variableTypeId = VariableType.Table,
              ),
              VariablesRow(
                  id = initialVariables[1].id,
                  internalOnly = false,
                  isList = false,
                  name = "Column A",
                  stableId = "2",
                  variableTypeId = VariableType.Number,
              ),
              VariablesRow(
                  id = initialVariables[2].id,
                  internalOnly = false,
                  isList = false,
                  name = "Column B",
                  stableId = "3",
                  variableTypeId = VariableType.Number,
              ),
              VariablesRow(
                  id = updatedVariables[3].id,
                  internalOnly = false,
                  isList = true,
                  name = "Table",
                  replacesVariableId = initialVariables[0].id,
                  stableId = "1",
                  variableTypeId = VariableType.Table,
              ),
              VariablesRow(
                  id = updatedVariables[4].id,
                  internalOnly = false,
                  isList = false,
                  name = "Renamed A",
                  replacesVariableId = initialVariables[1].id,
                  stableId = "2",
                  variableTypeId = VariableType.Number,
              ),
              VariablesRow(
                  id = updatedVariables[5].id,
                  internalOnly = false,
                  isList = false,
                  name = "Renamed B",
                  replacesVariableId = initialVariables[2].id,
                  stableId = "3",
                  variableTypeId = VariableType.Number,
              ),
          ),
          variablesDao.findAll().toSet(),
          "New variables are created for columns but not the table")
    }

    @Test
    fun `creates new table variable and new column variables if any columns have changed`() {
      val initialCsv =
          header +
              "\nTable,1,,Table,Yes,,,,,,,,,," +
              "\nColumn A,2,,Number,,,Table,,,,,,,," +
              "\nColumn B,3,,Number,,,Table,,,,,,,,"
      val updatedCsv =
          header +
              "\nTable,1,,Table,Yes,,,,,,,,,," +
              "\nColumn A,2,,Number,,,Table,,,,,,,," +
              "\nColumn B,3,,Number,,,Table,,,1,,,,,"

      importer.import(inserted.documentTemplateId, sizedInputStream(initialCsv))

      val initialVariables = variablesDao.findAll().sortedBy { it.id!!.value }
      assertEquals(3, initialVariables.size, "Should have imported 3 variables")

      val updateResult = importer.import(inserted.documentTemplateId, sizedInputStream(updatedCsv))

      val updatedVariables = variablesDao.findAll().sortedBy { it.id!!.value }
      assertEquals(6, updatedVariables.size, "Should have imported new copies of all variables")
      val newTableVariableId = updatedVariables[3].id!!

      assertEquals(
          setOf(
              VariableManifestEntriesRow(
                  position = 2,
                  variableId = newTableVariableId,
                  variableManifestId = updateResult.newVersion,
              ),
              VariableManifestEntriesRow(
                  position = 3,
                  variableId = updatedVariables[4].id,
                  variableManifestId = updateResult.newVersion,
              ),
              VariableManifestEntriesRow(
                  position = 4,
                  variableId = updatedVariables[5].id,
                  variableManifestId = updateResult.newVersion,
              ),
          ),
          variableManifestEntriesDao.fetchByVariableManifestId(updateResult.newVersion!!).toSet(),
          "New manifest should use new variables")

      assertEquals(
          setOf(updatedVariables[4].id, updatedVariables[5].id),
          variableTableColumnsDao
              .fetchByTableVariableId(newTableVariableId)
              .map { it.variableId }
              .toSet(),
          "New columns should be children of new table")
    }

    private fun sizedInputStream(content: ByteArray) =
        SizedInputStream(content.inputStream(), content.size.toLong())

    private fun sizedInputStream(content: String) = sizedInputStream(content.toByteArray())

    // This is not safe to use in tests where there are multiple variables with the same name, this
    // does not care about hierarchy and will grab the first one that matches by name in reversed
    // order
    private fun getVariableByName(name: String): VariablesRow =
        variablesDao.fetchByName(name).reversed().first()
  }
}

private data class FlatVariable(
    val variableId: VariableId,
    val variableName: String?,
    val parentId: VariableId?,
    val parentVariableName: String?,
)
