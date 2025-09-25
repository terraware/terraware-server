package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.accelerator.tables.records.DeliverableVariablesRecord
import com.terraformation.backend.db.docprod.DependencyCondition
import com.terraformation.backend.db.docprod.VariableTableStyle
import com.terraformation.backend.db.docprod.VariableTextType
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.tables.pojos.VariableNumbersRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectOptionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTableColumnsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTablesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTextsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariablesRow
import com.terraformation.backend.db.docprod.tables.records.VariableTableColumnsRecord
import com.terraformation.backend.db.docprod.tables.records.VariablesRecord
import com.terraformation.backend.documentproducer.db.variable.VariableImportResult
import com.terraformation.backend.documentproducer.db.variable.VariableImporter
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class VariableImporterTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val messages = Messages()

  private val deliverableStore: DeliverableStore by lazy { DeliverableStore(dslContext) }

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

  private val importer: VariableImporter by lazy {
    VariableImporter(deliverableStore, dslContext, messages, variableStore)
  }

  @Nested
  inner class UploadManifest {
    private val header =
        "Name,ID,Description,Data Type,List?,Parent,Options,Minimum value,Maximum value,Decimal places,Table Style,Header?,Notes,Deliverable Question,Dependency Variable ID,Dependency Condition,Dependency Value,Internal Only,Required"

    @Test
    fun `detects duplicate stable IDs`() {
      val testCsv =
          header +
              "\nName X,Duplicate ID,,Number,Yes,,,,,,,,,,,,,," +
              "\nName Y,Duplicate ID,,Number,Yes,,,,,,,,,,,,,,"

      val importResult = importer.import(sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: ID must be unique across the entire all variables CSV, Field: ID, Value: Duplicate ID, Position: 3"
          ),
          importResult.errors,
      )
    }

    @Test
    fun `correctly identifies when there is a non-unique top level input name`() {
      val testCsv =
          header +
              "\nDuplicate Name,1,,Number,,,,,,,,,,,,,,," +
              "\nDuplicate Name,2,,Number,,,,,,,,,,,,,,,"

      val importResult = importer.import(sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Top Level Variable Name must be unique, Field: Name, Value: Duplicate Name, Position: 3"
          ),
          importResult.errors,
          "Non-Unique Input Names",
      )
    }

    @Test
    fun `imports table variables correctly`() {
      val testCsv =
          header +
              "\nProject Proponent Table,1,A table with contact details,Table,No,,,,,,,,,,,,,," +
              "\nOrganization Name,2,,Text (single-line),,Project Proponent Table,,,,,,,,,,,,," +
              "\nContact Person,3,,Text (single-line),,Project Proponent Table,,,,,,,,,,,,," +
              "\nTitle,4,,Text (single-line),,Project Proponent Table,,,,,,,,,,,,,"

      val importResult = importer.import(sizedInputStream(testCsv))

      val actualTableVariable = getVariableByName("Project Proponent Table")
      val expectedTableVariable =
          VariablesRow(
              description = "A table with contact details",
              id = actualTableVariable.id!!,
              internalOnly = false,
              isList = false,
              isRequired = false,
              name = "Project Proponent Table",
              stableId = StableId("1"),
              variableTypeId = VariableType.Table,
          )

      val actualTableRow = variableTablesDao.fetchOneByVariableId(actualTableVariable.id!!)
      val expectedTableRow =
          VariableTablesRow(
              variableId = actualTableVariable.id!!,
              variableTypeId = VariableType.Table,
              tableStyleId = VariableTableStyle.Horizontal,
          )
      assertEquals(emptyList<String>(), importResult.errors, "no errors")
      assertEquals(
          expectedTableVariable,
          actualTableVariable,
          "Variable DB row for table is correct",
      )
      assertEquals(expectedTableRow, actualTableRow, "Variable Table DB row for table is correct")

      val tableColumnVariableRow1 = getVariableByName("Organization Name")
      val tableColumnVariableRow2 = getVariableByName("Contact Person")
      val tableColumnVariableRow3 = getVariableByName("Title")

      assertTableEquals(
          listOf(
              VariableTableColumnsRecord(
                  variableId = tableColumnVariableRow1.id!!,
                  tableVariableId = actualTableVariable.id!!,
                  tableVariableTypeId = VariableType.Table,
                  position = 1,
                  isHeader = false,
              ),
              VariableTableColumnsRecord(
                  variableId = tableColumnVariableRow2.id!!,
                  tableVariableId = actualTableVariable.id!!,
                  tableVariableTypeId = VariableType.Table,
                  position = 2,
                  isHeader = false,
              ),
              VariableTableColumnsRecord(
                  variableId = tableColumnVariableRow3.id!!,
                  tableVariableId = actualTableVariable.id!!,
                  tableVariableTypeId = VariableType.Table,
                  position = 3,
                  isHeader = false,
              ),
          ),
          "Variable Table Column DB rows are correct",
      )
    }

    @Test
    fun `imports complex table with select option header column correctly`() {
      val testCsv =
          header +
              "\nAudit history,1,,Table,Yes,,,,,,Horizontal,,,,,,,," +
              "\nAudit type,2,,Select (single),,Audit history,\"- Validation/verification" +
              "\n- Some other audit type\",,,,,Yes,,,,,,," +
              "\nNumber of years,3,,Number,,Audit history,,,,1,,,,,,,,,"

      val importResult = importer.import(sizedInputStream(testCsv))

      val actualTableVariable = getVariableByName("Audit history")

      val tableColumnVariableRow1 = getVariableByName("Audit type")
      val tableColumnVariableRow2 = getVariableByName("Number of years")

      val actualTableColumnRows = variableTableColumnsDao.findAll().toSet()
      val expectedTableColumnRows =
          setOf(
              VariableTableColumnsRow(
                  variableId = tableColumnVariableRow1.id!!,
                  tableVariableId = actualTableVariable.id!!,
                  tableVariableTypeId = VariableType.Table,
                  position = 1,
                  isHeader = true,
              ),
              VariableTableColumnsRow(
                  variableId = tableColumnVariableRow2.id!!,
                  tableVariableId = actualTableVariable.id!!,
                  tableVariableTypeId = VariableType.Table,
                  position = 2,
                  isHeader = false,
              ),
          )

      val actualNumberRow = variableNumbersDao.fetchOneByVariableId(tableColumnVariableRow2.id!!)
      val expectedNumberRow =
          VariableNumbersRow(
              variableId = tableColumnVariableRow2.id!!,
              variableTypeId = VariableType.Number,
              decimalPlaces = 1,
          )

      val actualSelectRow = variableSelectsDao.fetchOneByVariableId(tableColumnVariableRow1.id!!)
      val expectedSelectRow =
          VariableSelectsRow(
              variableId = tableColumnVariableRow1.id!!,
              variableTypeId = VariableType.Select,
              isMultiple = false,
          )

      val actualSelectOptionRows =
          variableSelectOptionsDao.fetchByVariableId(tableColumnVariableRow1.id!!)
      val expectedSelectOptionRows =
          listOf(
              VariableSelectOptionsRow(
                  variableId = tableColumnVariableRow1.id!!,
                  variableTypeId = VariableType.Select,
                  name = "Validation/verification",
                  position = 1,
              ),
              VariableSelectOptionsRow(
                  variableId = tableColumnVariableRow1.id!!,
                  variableTypeId = VariableType.Select,
                  name = "Some other audit type",
                  position = 2,
              ),
          )

      assertEquals(emptyList<String>(), importResult.errors, "no errors")
      assertEquals(
          expectedTableColumnRows,
          actualTableColumnRows,
          "Variable Table Column DB rows are correct",
      )
      assertEquals(expectedNumberRow, actualNumberRow, "Variable Number row is correct")
      assertEquals(expectedSelectRow, actualSelectRow, "Variable Select row is correct")
      assertEquals(
          expectedSelectOptionRows,
          actualSelectOptionRows.map { it.copy(id = null) },
          "Variable Select Option rows are correct",
      )
    }

    @Test
    fun `ensures that single and multi line text are saved into the DB as expected`() {
      val testCsv =
          header +
              "\nOrganization Name,1,,Text (single-line),,,,,,,,,,,,,,," +
              "\nOrganization Name As List,2,,Text (single-line),yes,,,,,,,,,,,,,," +
              "\nPrior Scenario,3,A brief description of the scenario,Text (multi-line),,,,,,,,,,,,,,," +
              "\nPrior Scenario As List,4,A brief description of the scenario,Text (multi-line),yes,,,,,,,,,,,,,,"

      val importResult = importer.import(sizedInputStream(testCsv))

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
              variableTextTypeId = VariableTextType.SingleLine,
          ),
          actualVariableTextO,
          "single line text variable saved correctly",
      )

      assertTrue(actualVariableOAL.isList!!, "single input with list is a list")
      assertEquals(
          VariableTextsRow(
              variableId = actualVariableOAL.id!!,
              variableTypeId = VariableType.Text,
              variableTextTypeId = VariableTextType.SingleLine,
          ),
          actualVariableTextOAL,
          "single line text variable saved correctly",
      )

      assertFalse(actualVariablePS.isList!!, "multi input without list is not a list")
      assertEquals(
          VariableTextsRow(
              variableId = actualVariablePS.id!!,
              variableTypeId = VariableType.Text,
              variableTextTypeId = VariableTextType.MultiLine,
          ),
          actualVariableTextPS,
          "multi line text variable saved correctly",
      )

      assertTrue(actualVariablePSAL.isList!!, "multi input with list is a list")
      assertEquals(
          VariableTextsRow(
              variableId = actualVariablePSAL.id!!,
              variableTypeId = VariableType.Text,
              variableTextTypeId = VariableTextType.MultiLine,
          ),
          actualVariableTextPSAL,
          "multi line text variable saved correctly",
      )
    }

    @Test
    fun `ensures that single and multi selects are saved into the DB as expected`() {
      val testCsv =
          header +
              // Single select
              "\nEstimated reductions,1,Canned description,Select (single),,,\"- tiny" +
              "\n- small" +
              "\n- medium" +
              "\n- large\",,,,,,,,,,,," +
              // Multi select
              "\nMade up multi select,2,Select as many as you want!,Select (multiple),,,\"- Option 1" +
              "\n- Option 2" +
              "\n- Option 3" +
              "\n- Option 4 [[This one has super special rendered text!]]" +
              "\n- Option 5\",,,,,,,,,,,,"

      val importResult = importer.import(sizedInputStream(testCsv))

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
          variableSelectEAGER?.isMultiple!!,
          "single select variable select is not multiple",
      )
      assertEquals(
          variableSelectOptionsEAGER.size,
          4,
          "single select variable select options were created",
      )

      assertEquals(
          VariableSelectOptionsRow(
              name = "medium",
              position = 3,
              variableId = variableEAGER.id,
              variableTypeId = variableEAGER.variableTypeId,
          ),
          variableSelectOption3EAGER?.copy(id = null),
          "single select option",
      )

      assertFalse(variableMUMS.isList!!, "multi select is not a list")
      assertNotNull(variableSelectMUMS, "multi select variable select was created")
      assertTrue(variableSelectMUMS?.isMultiple!!, "multi select variable select is multiple")
      assertEquals(
          variableSelectOptionsMUMS.size,
          5,
          "multi select variable select options were created",
      )

      assertEquals(
          VariableSelectOptionsRow(
              name = "Option 4",
              position = 4,
              renderedText = "This one has super special rendered text!",
              variableId = variableMUMS.id,
              variableTypeId = variableMUMS.variableTypeId,
          ),
          variableSelectOption4MUMS?.copy(id = null),
          "multi select option with rendered text",
      )
    }

    @Test
    fun `ensures that select options are unique within the select`() {
      val testCsv =
          header +
              "\nEstimated reductions,1,Canned Description,Select (single),,,\"- tiny amount" +
              "\n- small amount" +
              "\n- medium amount" +
              "\n- medium amount" +
              "\n- large amount\",,,,,,,,,,,,"

      val importResult = importer.import(sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Select option values must be unique within the select variable, Field: Options, Value: - tiny amount" +
                  "\n- small amount" +
                  "\n- medium amount" +
                  "\n- medium amount" +
                  "\n- large amount, Position: 2"
          ),
          importResult.errors,
          "duplicate option error",
      )
    }

    @Test
    fun `detects newlines in variable names`() {
      val testCsv = "$header\n\"Number\nName\",1,,Number,,,,,,,,,,,,,,,"

      val importResult = importer.import(sizedInputStream(testCsv))

      assertEquals(
          listOf(
              "Message: Name may not contain line breaks, Field: Name, Value: Number\nName, Position: 2"
          ),
          importResult.errors,
      )
    }

    @Test
    fun `detects missing names`() {
      val testCsv = header + "\n,1,,Section,Yes,,,,,,,,,,,,,,"

      val importResult = importer.import(sizedInputStream(testCsv))

      assertEquals(
          listOf("Message: Name column is required, Field: Name, Value: null, Position: 2"),
          importResult.errors,
      )
    }

    @Test
    fun `reuses existing select variable`() {
      val testCsv =
          header +
              "\nSelect Variable,A,,Select (single),,,\"- Option 1\n- Option 2\n- Option 3\",,,,,,,,,,,,"

      importer.import(sizedInputStream(testCsv))
      val updateResult = importer.import(sizedInputStream(testCsv))

      val variablesRows = variablesDao.findAll()
      assertEquals(1, variablesRows.size, "Number of imported variables")

      assertEquals(VariableImportResult(emptyList(), emptyMap()), updateResult)
    }

    @Test
    fun `creates new select variable if options have changed`() {
      val initialCsv =
          "$header\nSelect,1,,Select (single),,,\"- Option 1\n- Option 2\n- Option 3\",,,,,,,,,,,,"
      val updatedCsv = "$header\nSelect,1,,Select (single),,,\"- Option 1\n- Option 2\",,,,,,,,,,,,"

      importer.import(sizedInputStream(initialCsv))
      val originalVariableId = variablesDao.findAll().first().id!!

      val updateResult = importer.import(sizedInputStream(updatedCsv))

      val variablesRows = variablesDao.findAll().sortedBy { it.id }
      val updatedVariableId = variablesRows.filter { it.id != originalVariableId }.single().id!!

      assertEquals(2, variablesRows.size, "Number of imported variables")

      assertEquals(
          VariableImportResult(emptyList(), mapOf(originalVariableId to updatedVariableId)),
          updateResult,
      )

      assertSetEquals(
          setOf("Option 1", "Option 2", "Option 3"),
          variableSelectOptionsDao.fetchByVariableId(variablesRows[0].id!!).map { it.name }.toSet(),
          "Initial options",
      )
      assertSetEquals(
          setOf("Option 1", "Option 2"),
          variableSelectOptionsDao.fetchByVariableId(variablesRows[1].id!!).map { it.name }.toSet(),
          "Updated options",
      )
    }

    @Test
    fun `creates new variable if name or description have changed`() {
      val initialCsv =
          "$header\nOriginal variable,1,Original description,Text (single-line),,,,,,,,,,,,,,,"
      val updatedCsv =
          "$header\nUpdated variable,1,Updated description,Text (single-line),,,,,,,,,,,,,,,"

      importer.import(sizedInputStream(initialCsv))

      val initialVariables = variablesDao.findAll()
      assertEquals(1, initialVariables.size, "Should have imported 1 variable")
      val initialVariableId = initialVariables.first().id!!

      val updateResult = importer.import(sizedInputStream(updatedCsv))

      val updatedVariables = variablesDao.findAll()
      assertEquals(2, updatedVariables.size, "Should have imported 1 new variable")
      val updatedVariableId = updatedVariables.find { it.id != initialVariableId }!!.id!!

      assertEquals(
          VariableImportResult(emptyList(), mapOf(initialVariableId to updatedVariableId)),
          updateResult,
      )

      assertSetEquals(
          setOf(
              VariablesRow(
                  description = "Original description",
                  id = initialVariableId,
                  internalOnly = false,
                  isList = false,
                  isRequired = false,
                  name = "Original variable",
                  stableId = StableId("1"),
                  variableTypeId = VariableType.Text,
              ),
              VariablesRow(
                  description = "Updated description",
                  id = updatedVariableId,
                  internalOnly = false,
                  isList = false,
                  isRequired = false,
                  name = "Updated variable",
                  replacesVariableId = initialVariableId,
                  stableId = StableId("1"),
                  variableTypeId = VariableType.Text,
              ),
          ),
          variablesDao.findAll().toSet(),
          "New version of the variable should be present",
      )
    }

    @Test
    fun `creates new variable if validation settings have changed`() {
      val initialCsv = "$header\nNumber variable,1,,Number,,,,10,,,,,,,,,,,"
      val updatedCsv = "$header\nNumber variable,1,,Number,,,,20,,,,,,,,,,,"

      importer.import(sizedInputStream(initialCsv))

      val initialVariables = variablesDao.findAll()
      assertEquals(1, initialVariables.size, "Should have imported 1 variable")
      val initialVariableId = initialVariables.first().id!!

      val updateResult = importer.import(sizedInputStream(updatedCsv))

      val updatedVariables = variablesDao.findAll()
      assertEquals(2, updatedVariables.size, "Should have imported new copy of variable")
      val newVariableId = updatedVariables.map { it.id!! }.first { it != initialVariableId }

      assertEquals(
          VariableImportResult(emptyList(), mapOf(initialVariableId to newVariableId)),
          updateResult,
      )

      assertEquals(
          initialVariableId,
          updatedVariables.first { it.id == newVariableId }.replacesVariableId,
          "New variable should be marked as replacement of existing one",
      )

      assertSetEquals(
          setOf(
              VariablesRow(
                  id = initialVariableId,
                  internalOnly = false,
                  isList = false,
                  isRequired = false,
                  name = "Number variable",
                  stableId = StableId("1"),
                  variableTypeId = VariableType.Number,
              ),
              VariablesRow(
                  id = newVariableId,
                  internalOnly = false,
                  isList = false,
                  isRequired = false,
                  name = "Number variable",
                  replacesVariableId = initialVariableId,
                  stableId = StableId("1"),
                  variableTypeId = VariableType.Number,
              ),
          ),
          variablesDao.findAll().toSet(),
          "Both versions of the variable are present",
      )
    }

    @Test
    fun `creates new table and column variables if the column names are updated`() {
      val initialCsv =
          header +
              "\nTable,1,,Table,Yes,,,,,,,,,,,,,," +
              "\nColumn A,2,,Number,,Table,,,,,,,,,,,,," +
              "\nColumn B,3,,Number,,Table,,,,,,,,,,,,,"
      val updatedCsv =
          header +
              "\nTable,1,,Table,Yes,,,,,,,,,,,,,," +
              "\nRenamed A,2,,Number,,Table,,,,,,,,,,,,," +
              "\nRenamed B,3,,Number,,Table,,,,,,,,,,,,,"

      importer.import(sizedInputStream(initialCsv))

      val initialVariables = variablesDao.findAll().sortedBy { it.id }
      assertEquals(3, initialVariables.size, "Should have imported 3 variables")

      val updateResult = importer.import(sizedInputStream(updatedCsv))

      val updatedVariables = variablesDao.findAll().sortedBy { it.id }
      assertEquals(6, updatedVariables.size, "Should have created 2 new variables")

      assertEquals(
          VariableImportResult(
              emptyList(),
              mapOf(
                  initialVariables[0].id!! to updatedVariables[3].id!!,
                  initialVariables[1].id!! to updatedVariables[4].id!!,
                  initialVariables[2].id!! to updatedVariables[5].id!!,
              ),
          ),
          updateResult,
      )

      assertSetEquals(
          setOf(
              VariablesRow(
                  id = initialVariables[0].id,
                  internalOnly = false,
                  isList = true,
                  isRequired = false,
                  name = "Table",
                  stableId = StableId("1"),
                  variableTypeId = VariableType.Table,
              ),
              VariablesRow(
                  id = initialVariables[1].id,
                  internalOnly = false,
                  isList = false,
                  isRequired = false,
                  name = "Column A",
                  stableId = StableId("2"),
                  variableTypeId = VariableType.Number,
              ),
              VariablesRow(
                  id = initialVariables[2].id,
                  internalOnly = false,
                  isList = false,
                  isRequired = false,
                  name = "Column B",
                  stableId = StableId("3"),
                  variableTypeId = VariableType.Number,
              ),
              VariablesRow(
                  id = updatedVariables[3].id,
                  internalOnly = false,
                  isList = true,
                  isRequired = false,
                  name = "Table",
                  replacesVariableId = initialVariables[0].id,
                  stableId = StableId("1"),
                  variableTypeId = VariableType.Table,
              ),
              VariablesRow(
                  id = updatedVariables[4].id,
                  internalOnly = false,
                  isList = false,
                  isRequired = false,
                  name = "Renamed A",
                  replacesVariableId = initialVariables[1].id,
                  stableId = StableId("2"),
                  variableTypeId = VariableType.Number,
              ),
              VariablesRow(
                  id = updatedVariables[5].id,
                  internalOnly = false,
                  isList = false,
                  isRequired = false,
                  name = "Renamed B",
                  replacesVariableId = initialVariables[2].id,
                  stableId = StableId("3"),
                  variableTypeId = VariableType.Number,
              ),
          ),
          variablesDao.findAll().toSet(),
          "New variables are created for columns and table",
      )
    }

    @Test
    fun `creates new table variable and new column variables if any columns have changed`() {
      val initialCsv =
          header +
              "\nTable,1,,Table,Yes,,,,,,,,,,,,,," +
              "\nColumn A,2,,Number,,Table,,,,,,,,,,,,," +
              "\nColumn B,3,,Number,,Table,,,,,,,,,,,,,"
      val updatedCsv =
          header +
              "\nTable,1,,Table,Yes,,,,,,,,,,,,,," +
              "\nColumn A,2,,Number,,Table,,,,,,,,,,,,," +
              "\nColumn B,3,,Number,,Table,,1,,,,,,,,,,,"

      importer.import(sizedInputStream(initialCsv))

      val initialVariables = variablesDao.findAll().sortedBy { it.id }
      assertEquals(3, initialVariables.size, "Should have imported 3 variables")

      val updateResult = importer.import(sizedInputStream(updatedCsv))

      val updatedVariables = variablesDao.findAll().sortedBy { it.id }
      assertEquals(6, updatedVariables.size, "Should have imported new copies of all variables")
      val newTableVariableId = updatedVariables[3].id!!

      assertEquals(
          VariableImportResult(
              emptyList(),
              mapOf(
                  initialVariables[0].id!! to newTableVariableId,
                  initialVariables[1].id!! to updatedVariables[4].id!!,
                  initialVariables[2].id!! to updatedVariables[5].id!!,
              ),
          ),
          updateResult,
      )

      assertSetEquals(
          setOf(updatedVariables[4].id, updatedVariables[5].id),
          variableTableColumnsDao
              .fetchByTableVariableId(newTableVariableId)
              .map { it.variableId }
              .toSet(),
          "New columns should be children of new table",
      )
    }

    @Test
    fun `creates new table variable and new variables for existing columns if new column is added`() {
      val initialCsv =
          header + "\nTable,1,,Table,Yes,,,,,,,,,,,,,," + "\nColumn A,2,,Number,,Table,,,,,,,,,,,,,"
      val updatedCsv =
          header +
              "\nTable,1,,Table,Yes,,,,,,,,,,,,,," +
              "\nColumn A,2,,Number,,Table,,,,,,,,,,,,," +
              "\nColumn B,3,,Number,,Table,,1,,,,,,,,,,,"

      importer.import(sizedInputStream(initialCsv))

      val initialVariables = variablesDao.findAll().sortedBy { it.id }
      assertEquals(2, initialVariables.size, "Should have imported 2 variables")

      val updateResult = importer.import(sizedInputStream(updatedCsv))

      val updatedVariables = variablesDao.findAll().sortedBy { it.id }
      assertEquals(5, updatedVariables.size, "Should have imported new copies of all variables")
      val newTableVariableId = updatedVariables[2].id!!

      assertEquals(
          VariableImportResult(
              emptyList(),
              mapOf(
                  initialVariables[0].id!! to newTableVariableId,
                  initialVariables[1].id!! to updatedVariables[3].id!!,
              ),
          ),
          updateResult,
      )

      assertSetEquals(
          setOf(updatedVariables[3].id, updatedVariables[4].id),
          variableTableColumnsDao
              .fetchByTableVariableId(newTableVariableId)
              .map { it.variableId }
              .toSet(),
          "New columns should be children of new table",
      )
    }

    @Test
    fun `tables are lists by default`() {
      val csv = "$header\nTable,1,,Table,,,,,,,,,,,,,,,"

      importer.import(sizedInputStream(csv))

      val variables = variablesDao.findAll()

      assertEquals(true, variables[0].isList, "Table is list")
    }

    @Test
    fun `tables can be specified as non-lists`() {
      val csv = "$header\nTable,1,,Table,No,,,,,,,,,,,,,,"

      importer.import(sizedInputStream(csv))

      val variables = variablesDao.findAll()

      assertEquals(false, variables[0].isList, "Table is list")
    }

    @Test
    fun `saves deliverable related fields as expected`() {
      every { user.canReadAllDeliverables() } returns true

      val csv =
          header +
              "\nNumber of non-native species,1115,,Number,,,,,,,,,,What number of non-native species will you plant in this project?,,,,true,true" +
              "\nReason to use non-native species,1116,,Select (multiple),,,\"- agroforestry timber" +
              "\n- sustainable timber" +
              "\n- marketable product\",,,,,,,What is the reason these non-native species are being planted?,1115,>=,5,true,false" +
              "\nTable,1117,,Table,Yes,,,,,,,,,This is a table,,,,true,true" +
              "\nColumn,1118,,Number,,Table,,,,,,,,This is a column,,,,true,true"

      importer.import(sizedInputStream(csv))

      assertTableEquals(
          listOf(
              VariablesRecord(
                  deliverableQuestion =
                      "What number of non-native species will you plant in this project?",
                  internalOnly = true,
                  isList = false,
                  isRequired = true,
                  name = "Number of non-native species",
                  stableId = StableId("1115"),
                  variableTypeId = VariableType.Number,
              ),
              VariablesRecord(
                  deliverableQuestion =
                      "What is the reason these non-native species are being planted?",
                  dependencyConditionId = DependencyCondition.Gte,
                  dependencyVariableStableId = StableId("1115"),
                  dependencyValue = "5",
                  internalOnly = true,
                  isList = false,
                  isRequired = false,
                  name = "Reason to use non-native species",
                  stableId = StableId("1116"),
                  variableTypeId = VariableType.Select,
              ),
              VariablesRecord(
                  deliverableQuestion = "This is a table",
                  internalOnly = true,
                  isList = true,
                  isRequired = true,
                  name = "Table",
                  stableId = StableId("1117"),
                  variableTypeId = VariableType.Table,
              ),
              VariablesRecord(
                  deliverableQuestion = "This is a column",
                  internalOnly = true,
                  isList = false,
                  isRequired = true,
                  name = "Column",
                  stableId = StableId("1118"),
                  variableTypeId = VariableType.Number,
              ),
          ),
          "New variables are created with deliverable related fields",
      )
    }

    @Test
    fun `updates deliverables to use new versions of replaced variables`() {
      every { user.canReadAllDeliverables() } returns true

      insertModule()
      val deliverableId1 = insertDeliverable()
      val deliverableId2 = insertDeliverable()
      val variableId1111 = insertNumberVariable(stableId = "1111")
      insertDeliverableVariable(deliverableId = deliverableId1, position = 0)
      insertNumberVariable(stableId = "1112")
      insertDeliverableVariable(deliverableId = deliverableId2, position = 0)
      insertNumberVariable(stableId = "1113")
      insertDeliverableVariable(deliverableId = deliverableId1, position = 1)

      val variables = variablesDao.findAll().sortedBy { it.id }

      val csv =
          header +
              "\nUpdated Variable 1,1111,,Number,,,,,,,,,,,,,,," +
              "\nVariable 2,1112,,Number,,,,,,,,,,,,,,," +
              "\nVariable 3,1113,,Number,,,,,,,,,,,,,,,"

      importer.import(sizedInputStream(csv))

      val updatedVariables = variablesDao.findAll().sortedBy { it.id }

      assertTableEquals(
          listOf(
              VariablesRecord(
                  internalOnly = false,
                  isList = false,
                  isRequired = false,
                  name = "Variable 1",
                  stableId = StableId("1111"),
                  variableTypeId = VariableType.Number,
              ),
              VariablesRecord(
                  internalOnly = false,
                  isList = false,
                  isRequired = false,
                  name = "Variable 2",
                  stableId = StableId("1112"),
                  variableTypeId = VariableType.Number,
              ),
              VariablesRecord(
                  internalOnly = false,
                  isList = false,
                  isRequired = false,
                  name = "Variable 3",
                  stableId = StableId("1113"),
                  variableTypeId = VariableType.Number,
              ),
              VariablesRecord(
                  internalOnly = false,
                  isList = false,
                  isRequired = false,
                  name = "Updated Variable 1",
                  replacesVariableId = variableId1111,
                  stableId = StableId("1111"),
                  variableTypeId = VariableType.Number,
              ),
          ),
          "New variable is created",
      )

      assertTableEquals(
          listOf(
              DeliverableVariablesRecord(deliverableId1, updatedVariables[3].id, 0),
              DeliverableVariablesRecord(deliverableId2, variables[1].id, 0),
              DeliverableVariablesRecord(deliverableId1, variables[2].id, 1),
          ),
          "Deliverable variables are updated as expected",
      )
    }

    @Test
    fun `returns an error if a dependency stable variable ID is referenced that does not exist`() {
      every { user.canReadAllDeliverables() } returns true

      val nonexistentDependencyVariableStableId = StableId("1111")

      val csv =
          header +
              "\nNumber of non-native species,1115,,Number,,,,,,,,,,What number of non-native species will you plant in this project?,,,,true," +
              "\nReason to use non-native species,1116,,Select (multiple),,,\"- agroforestry timber" +
              "\n- sustainable timber" +
              "\n- marketable product\",,,,,,,What is the reason these non-native species are being planted?,$nonexistentDependencyVariableStableId,>=,5,true,"

      val result = importer.import(sizedInputStream(csv))

      assertEquals(
          listOf(
              "Message: Supplied Dependency Variable Stable ID does not exist, Field: Dependency Variable Stable ID, Value: $nonexistentDependencyVariableStableId, Position: 3"
          ),
          result.errors,
      )
    }

    @Test
    fun `returns an error if a dependency stable variable ID is referencing itself`() {
      every { user.canReadAllDeliverables() } returns true

      val stableId = StableId("1116")

      val csv =
          header +
              "\nNumber of non-native species,1115,,Number,,,,,,,,,,What number of non-native species will you plant in this project?,,,,true," +
              "\nReason to use non-native species,$stableId,,Select (multiple),,,\"- agroforestry timber" +
              "\n- sustainable timber" +
              "\n- marketable product\",,,,,,,What is the reason these non-native species are being planted?,$stableId,>=,5,true,"

      val result = importer.import(sizedInputStream(csv))

      assertEquals(
          listOf(
              "Message: Supplied Dependency Variable Stable ID is the same as this variable's Stable ID, Field: Dependency Variable Stable ID, Value: $stableId, Position: 3"
          ),
          result.errors,
      )
    }

    @Test
    fun `returns an error if the dependency configuration supplied is not complete`() {
      every { user.canReadAllDeliverables() } returns true

      // Dependency Config has:
      // variable stable ID, condition, value
      //        1115       ,    >=    ,   5
      val missingDependencyVariableStableId = ",>=,5"
      val missingDependencyCondition = "1115,,5"
      val missingDependencyValue = "1115,>=,"

      val csvMissingDependencyVariableStableId =
          header +
              "\nNumber of non-native species,1115,,Number,,,,,,,,,,What number of non-native species will you plant in this project?,,,,true," +
              "\nReason to use non-native species,1116,,Select (multiple),,,\"- agroforestry timber" +
              "\n- sustainable timber" +
              "\n- marketable product\",,,,,,,What is the reason these non-native species are being planted?,$missingDependencyVariableStableId,true,"

      val result1 = importer.import(sizedInputStream(csvMissingDependencyVariableStableId))
      assertEquals(
          listOf(
              "Message: Supplied Dependency Configuration is incomplete: Missing field, Field: Dependency Variable Stable ID, Value: null, Position: 3"
          ),
          result1.errors,
      )

      val csvMissingDependencyCondition =
          header +
              "\nNumber of non-native species,1115,,Number,,,,,,,,,,What number of non-native species will you plant in this project?,,,,true," +
              "\nReason to use non-native species,1116,,Select (multiple),,,\"- agroforestry timber" +
              "\n- sustainable timber" +
              "\n- marketable product\",,,,,,,What is the reason these non-native species are being planted?,$missingDependencyCondition,true,"

      val result2 = importer.import(sizedInputStream(csvMissingDependencyCondition))
      assertEquals(
          listOf(
              "Message: Supplied Dependency Configuration is incomplete: Missing field, Field: Dependency Condition, Value: null, Position: 3"
          ),
          result2.errors,
      )

      val csvMissingDependencyValue =
          header +
              "\nNumber of non-native species,1115,,Number,,,,,,,,,,What number of non-native species will you plant in this project?,,,,true," +
              "\nReason to use non-native species,1116,,Select (multiple),,,\"- agroforestry timber" +
              "\n- sustainable timber" +
              "\n- marketable product\",,,,,,,What is the reason these non-native species are being planted?,$missingDependencyValue,true,"

      val result3 = importer.import(sizedInputStream(csvMissingDependencyValue))
      assertEquals(
          listOf(
              "Message: Supplied Dependency Configuration is incomplete: Missing field, Field: Dependency Value, Value: null, Position: 3"
          ),
          result3.errors,
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
