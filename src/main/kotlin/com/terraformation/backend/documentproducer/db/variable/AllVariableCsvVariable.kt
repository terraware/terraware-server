package com.terraformation.backend.documentproducer.db.variable

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.docprod.DependencyCondition
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableTableStyle
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectOptionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariablesRow
import java.math.BigDecimal

private const val LOCALIZED_ERROR_KEY_UNKNOWN_DATA_TYPE = "variablesCsvDataTypeUnknown"

// In the header order in the CSV
data class AllVariableCsvVariable(
    /** Column 1/A - Name */
    val name: String,
    /** Column 2/B - Identifier that stays stable across settings changes. */
    val stableId: String,
    /** Column 3/C - Description (optional) */
    val description: String?,
    /** Column 4/D - Data Type */
    val dataType: AllVariableCsvVariableType,
    /** Column 5/E - List? */
    val isList: Boolean,
    /** Column 6/F - Parent (for non-top-level sections and table columns) */
    val parent: String?,
    /**
     * Column 7/G - Options (for Select; put each value on a separate line, optionally prefixed with
     * a hyphen; if the value should result in different text in the rendered document, put the text
     * in double square brackets after the option)
     */
    val options: List<VariableSelectOptionsRow>,
    /** Column 8/H - Minimum value, if any (for Number) */
    val minValue: BigDecimal?,
    /** Column 9/I - Maximum value, if any (for Number) */
    val maxValue: BigDecimal?,
    /** Column 10/J - Decimal places (for Number) */
    val decimalPlaces: Int?,
    /** Column 11/K - Table style (for Table) */
    val tableStyle: VariableTableStyle?,
    /** Column 12/L - Header? (for table columns) */
    val isHeader: Boolean,
    /** Column 13/M - Notes */
    val notes: String?,
    /** Column 14/N - Deliverable ID */
    val deliverableId: DeliverableId?,
    /** Column 15/O - Deliverable Question */
    val deliverableQuestion: String?,
    /** Column 16/P - Dependency - Variable Stable ID */
    val dependencyVariableStableId: String?,
    /** Column 17/Q - Dependency - Condition */
    val dependencyCondition: DependencyCondition?,
    /** Column 18/R - Dependency - Value */
    val dependencyValue: String?,
    /** Column 19/S - Internal Only */
    val internalOnly: Boolean,
    /** Column 20/T - Required? */
    val isRequired: Boolean,

    /** These are calculated during the import */
    /** The position of this variable within the associated deliverable, if applicable * */
    var deliverablePosition: Int?,
    /** The path to the parent variable within the hierarchy */
    var parentPath: String?,
    /** Position in the sheet, recorded against the variable manifest entry */
    val position: Int,
    /** If this variable replaces one from an earlier manifest, that variable's ID. */
    var replacesVariableId: VariableId? = null,
    /** The base variable ID */
    var variableId: VariableId? = null,
    /** The path to the variable within the hierarchy */
    var variablePath: String,
) {
  fun mapToVariablesRow() =
      VariablesRow(
          deliverableQuestion = deliverableQuestion,
          dependencyVariableStableId = dependencyVariableStableId,
          dependencyConditionId = dependencyCondition,
          dependencyValue = dependencyValue,
          description = description,
          id = variableId,
          internalOnly = internalOnly,
          isList = isList,
          isRequired = isRequired,
          name = name,
          replacesVariableId = replacesVariableId,
          stableId = stableId,
          variableTypeId = dataType.variableType,
      )
}

enum class AllVariableCsvVariableType(val value: String, val variableType: VariableType) {
  Number("Number", VariableType.Number),
  SingleLine("Text (single-line)", VariableType.Text),
  MultiLine("Text (multi-line)", VariableType.Text),
  SingleSelect("Select (single)", VariableType.Select),
  MultiSelect("Select (multiple)", VariableType.Select),
  Date("Date", VariableType.Date),
  Link("Link", VariableType.Link),
  Image("Image", VariableType.Image),
  Table("Table", VariableType.Table);

  companion object {
    private val byValue = entries.associateBy { it.value }

    fun create(input: String): AllVariableCsvVariableType =
        byValue[input] ?: throw IllegalArgumentException(LOCALIZED_ERROR_KEY_UNKNOWN_DATA_TYPE)
  }
}
