package com.terraformation.backend.documentproducer.db.manifest

import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableTableStyle
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.embeddables.pojos.VariableManifestEntryId
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectOptionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariablesRow
import java.math.BigDecimal

private const val LOCALIZED_ERROR_KEY_UNKNOWN_DATA_TYPE = "variablesCsvDataTypeUnknown"

// In the header order in the CSV
data class CsvVariable(
    /** Column 1/A - Name */
    val name: String,
    /** Column 2/B - Identifier that stays stable across settings changes. */
    val stableId: String,
    /** Column 3/C - Description (optional) */
    val description: String?,
    /** Column 4/D - Data Type */
    val dataType: CsvVariableType,
    /** Column 5/E - List? */
    val isList: Boolean,
    /** Column 6/F - Recommended variables (one name per line, optionally prefixed with a hyphen) */
    val recommendedVariables: List<String>,
    /** Column 7/G - Parent (for non-top-level sections and table columns) */
    val parent: String?,
    /**
     * Column 8/H - Non-numbered section? (for sections that aren't assigned a number in the section
     * hierarchy; typically a leaf-level section)
     */
    val isNonNumberedSection: Boolean,
    /**
     * Column 9/I - Options (for Select; put each value on a separate line, optionally prefixed with
     * a hyphen; if the value should result in different text in the rendered document, put the text
     * in double square brackets after the option)
     */
    val options: List<VariableSelectOptionsRow>,
    /** Column 10/J - Minimum value, if any (for Number) */
    val minValue: BigDecimal?,
    /** Column 11/K - Maximum value, if any (for Number) */
    val maxValue: BigDecimal?,
    /** Column 12/L - Decimal places (for Number) */
    val decimalPlaces: Int?,
    /** Column 13/M - Table style (for Table) */
    val tableStyle: VariableTableStyle?,
    /** Column 14/N - Header? (for table columns) */
    val isHeader: Boolean,
    /** Column 15/O - Notes */
    val notes: String?,

    /** Position in the sheet, recorded against the variable manifest entry */
    val position: Int,
    /** The path to the variable within the hierarchy */
    var variablePath: String,
    /** The path to the parent variable within the hierarchy */
    var parentPath: String?,
    /** The base variable ID */
    var variableId: VariableId? = null,
    /** If this variable replaces one from an earlier manifest, that variable's ID. */
    var replacesVariableId: VariableId? = null,
    /** Primary key for the variable manifest entry since there is no ID for this entity */
    var variableManifestEntryKey: VariableManifestEntryId? = null,
) {
  fun mapToVariablesRow() =
      VariablesRow(
          description = description,
          id = variableId,
          isList = isList,
          name = name,
          replacesVariableId = replacesVariableId,
          stableId = stableId,
          variableTypeId = dataType.variableType,
      )
}

enum class CsvVariableType(val value: String, val variableType: VariableType) {
  Number("Number", VariableType.Number),
  SingleLine("Text (single-line)", VariableType.Text),
  MultiLine("Text (multi-line)", VariableType.Text),
  SingleSelect("Select (single)", VariableType.Select),
  MultiSelect("Select (multiple)", VariableType.Select),
  Date("Date", VariableType.Date),
  Link("Link", VariableType.Link),
  Image("Image", VariableType.Image),
  Table("Table", VariableType.Table),
  Section("Section", VariableType.Section);

  companion object {
    private val byValue = entries.associateBy { it.value }

    fun create(input: String): CsvVariableType =
        byValue[input] ?: throw IllegalArgumentException(LOCALIZED_ERROR_KEY_UNKNOWN_DATA_TYPE)
  }
}
