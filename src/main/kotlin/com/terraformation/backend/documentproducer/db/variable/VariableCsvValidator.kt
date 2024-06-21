package com.terraformation.backend.documentproducer.db.variable

import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.documentproducer.db.CsvValidator
import com.terraformation.backend.i18n.Messages

// Column 1/A
const val VARIABLE_CSV_COLUMN_INDEX_NAME = 0
// Column 2/B
const val VARIABLE_CSV_COLUMN_INDEX_STABLE_ID = VARIABLE_CSV_COLUMN_INDEX_NAME + 1
// Column 3/C
const val VARIABLE_CSV_COLUMN_INDEX_DESCRIPTION = VARIABLE_CSV_COLUMN_INDEX_STABLE_ID + 1
// Column 4/D
const val VARIABLE_CSV_COLUMN_INDEX_DATA_TYPE = VARIABLE_CSV_COLUMN_INDEX_DESCRIPTION + 1
// Column 5/E
const val VARIABLE_CSV_COLUMN_INDEX_IS_LIST = VARIABLE_CSV_COLUMN_INDEX_DATA_TYPE + 1
// Column 6/F
const val VARIABLE_CSV_COLUMN_INDEX_PARENT = VARIABLE_CSV_COLUMN_INDEX_IS_LIST + 1
// Column 7/G
const val VARIABLE_CSV_COLUMN_INDEX_OPTIONS = VARIABLE_CSV_COLUMN_INDEX_PARENT + 1
// Column 8/H
const val VARIABLE_CSV_COLUMN_INDEX_MIN_VALUE = VARIABLE_CSV_COLUMN_INDEX_OPTIONS + 1
// Column 9/I
const val VARIABLE_CSV_COLUMN_INDEX_MAX_VALUE = VARIABLE_CSV_COLUMN_INDEX_MIN_VALUE + 1
// Column 10/J
const val VARIABLE_CSV_COLUMN_INDEX_DECIMAL_PLACES = VARIABLE_CSV_COLUMN_INDEX_MAX_VALUE + 1
// Column 11/K
const val VARIABLE_CSV_COLUMN_INDEX_TABLE_STYLE = VARIABLE_CSV_COLUMN_INDEX_DECIMAL_PLACES + 1
// Column 12/L
const val VARIABLE_CSV_COLUMN_INDEX_IS_HEADER = VARIABLE_CSV_COLUMN_INDEX_TABLE_STYLE + 1
// Column 13/M
const val VARIABLE_CSV_COLUMN_INDEX_NOTES = VARIABLE_CSV_COLUMN_INDEX_IS_HEADER + 1
// Column 14/N
const val VARIABLE_CSV_COLUMN_INDEX_DELIVERABLE_ID = VARIABLE_CSV_COLUMN_INDEX_NOTES + 1
// Column 15/O
const val VARIABLE_CSV_COLUMN_INDEX_DELIVERABLE_QUESTION =
    VARIABLE_CSV_COLUMN_INDEX_DELIVERABLE_ID + 1
// Column 16/P
const val VARIABLE_CSV_COLUMN_INDEX_DELIVERABLE_QUESTION_POSITION =
    VARIABLE_CSV_COLUMN_INDEX_DELIVERABLE_QUESTION + 1
// Column 17/Q
const val VARIABLE_CSV_COLUMN_INDEX_DEPENDENCY_VARIABLE_STABLE_ID =
    VARIABLE_CSV_COLUMN_INDEX_DELIVERABLE_QUESTION_POSITION + 1
// Column 18/R
const val VARIABLE_CSV_COLUMN_INDEX_DEPENDENCY_CONDITION =
    VARIABLE_CSV_COLUMN_INDEX_DEPENDENCY_VARIABLE_STABLE_ID + 1
// Column 19/S
const val VARIABLE_CSV_COLUMN_INDEX_DEPENDENCY_VALUE =
    VARIABLE_CSV_COLUMN_INDEX_DEPENDENCY_CONDITION + 1
// Column 20/T
const val VARIABLE_CSV_COLUMN_INDEX_INTERNAL_ONLY = VARIABLE_CSV_COLUMN_INDEX_DEPENDENCY_VALUE + 1

class VariableCsvValidator(messages: Messages, val deliverableStore: DeliverableStore) :
    CsvValidator(messages) {
  private val existingStableIds = mutableSetOf<String>()

  private val existingDeliverableIds = mutableSetOf<DeliverableId>()

  private val parentPathToChildrenNamesMap = mutableMapOf<String, MutableSet<String>>()
  private val variableTypeByPath = mutableMapOf<String, AllVariableCsvVariableType>()

  override val validators: List<((String?, String) -> Unit)?> =
      listOf(
          this::validateName,
          this::validateStableId,
          null,
          null,
          null,
          null,
          this::validateOptions,
          null,
          null,
          null,
          null,
          null,
          null,
          this::validateDeliverable,
          null,
          null,
          this::validateDependencyVariableStableId,
          null,
          null,
          null,
      )

  override val rowValidators: List<((List<String?>) -> Unit)> = listOf(this::validateRow)

  override fun getColumnName(position: Int): String {
    return messages.variablesCsvColumnName(position)
  }

  private fun validateDeliverable(value: String?, field: String) {
    if (value.isNullOrBlank()) {
      return
    }

    val deliverableId = DeliverableId(value)

    if (deliverableId in existingDeliverableIds) {
      return
    } else if (!deliverableStore.deliverableIdExists(deliverableId)) {
      addError(field, value, messages.variableCsvDeliverableDoesNotExist())
    }

    existingDeliverableIds.add(deliverableId)
  }

  /** If there is a deliverable ID, a position must be provided as well */
  private fun validateDeliverableConfiguration(values: List<String?>) {
    if (values[VARIABLE_CSV_COLUMN_INDEX_DELIVERABLE_ID].isNullOrBlank()) {
      return
    }

    if (values[VARIABLE_CSV_COLUMN_INDEX_DELIVERABLE_QUESTION_POSITION].isNullOrBlank()) {
      addError(
          getColumnName(VARIABLE_CSV_COLUMN_INDEX_DELIVERABLE_QUESTION_POSITION),
          null,
          messages.variableCsvDeliverablePositionRequired())
    }
  }

  private fun validateDependencyVariableStableId(value: String?, field: String) {
    if (value.isNullOrBlank()) {
      return
    }

    if (value !in existingStableIds) {
      addError(field, value, messages.variablesCsvDependencyVariableStableIdDoesNotExist())
    }
  }

  /**
   * Validates that the dependency configuration supplied, dictated by 3 fields, is complete. If any
   * part of the configuration is supplied, the entire configuration must be supplied.
   */
  private fun validateDependencyConfiguration(values: List<String?>) {
    val columns =
        listOf(
            VARIABLE_CSV_COLUMN_INDEX_DEPENDENCY_VARIABLE_STABLE_ID,
            VARIABLE_CSV_COLUMN_INDEX_DEPENDENCY_CONDITION,
            VARIABLE_CSV_COLUMN_INDEX_DEPENDENCY_VALUE)
    val missingColumns = columns.filter { values[it] == null }

    if (missingColumns.isNotEmpty() && missingColumns.size != columns.size) {
      missingColumns.forEach { index ->
        val columnName = getColumnName(index)
        addError(columnName, null, messages.variablesCsvDependencyConfigIncomplete())
      }
    }
  }

  private fun validateName(value: String?, field: String) {
    if (value.isNullOrBlank()) {
      addError(field, value, messages.variablesCsvNameRequired())
    } else if (value.contains('\n')) {
      addError(field, value, messages.variableCsvNameLineBreak())
    }
  }

  private fun validateStableId(value: String?, field: String) {
    if (value.isNullOrBlank()) {
      addError(field, value, messages.variablesCsvStableIdRequired())
    } else if (value in existingStableIds) {
      addError(field, value, messages.variablesCsvStableIdNotUnique())
    } else {
      existingStableIds.add(value)
    }
  }

  private fun validateOptions(value: String?, field: String) {
    // Options must be unique within the select
    if (value == null) {
      return
    }

    // Make sure there are no duplicates
    val options = splitAndTrimNewline(value).map { scrubHyphenPrefix(it) }
    if (options.distinct().size != options.size) {
      addError(
          field,
          value,
          messages.variablesCsvSelectOptionsNotUnique(),
      )
    }
  }

  private fun validateRow(values: List<String?>) {
    val name = values[VARIABLE_CSV_COLUMN_INDEX_NAME]?.trim()
    if (name.isNullOrEmpty()) {
      // Error was already added in validateName()
      return
    }

    val parent = values[VARIABLE_CSV_COLUMN_INDEX_PARENT]

    // Make sure there are no siblings with the same name
    val parentPath =
        if (parent.isNullOrEmpty()) {
          // If no parent, path = empty string (top level)
          ""
        } else {
          // Find nearest parent path by parent name
          parentPathToChildrenNamesMap.keys.findLast { it.endsWith("\t$parent") }
        }

    if (parentPath == null) {
      // If this is not top level, and we couldn't find the closest parent, then we are not
      // correctly adding parent paths to the in memory map
      addError(
          messages.variablesCsvColumnName(VARIABLE_CSV_COLUMN_INDEX_PARENT),
          name,
          messages.variablesCsvVariableParentDoesNotExist())
      return
    }

    val siblings = parentPathToChildrenNamesMap[parentPath]
    if (siblings == null) {
      // Add this as the first child to this parent path
      parentPathToChildrenNamesMap[parentPath] = mutableSetOf(name)
    } else if (name in siblings) {
      val message =
          if (parent.isNullOrEmpty()) {
            // Empty parent means this is a top-level variable.
            messages.variablesCsvTopLevelNameNotUnique()
          } else {
            messages.variablesCsvVariableNameNotUniqueWithinParent()
          }

      addError(messages.variablesCsvColumnName(VARIABLE_CSV_COLUMN_INDEX_NAME), name, message)
      return
    } else {
      // Append this as another child to this parent path
      parentPathToChildrenNamesMap[parentPath]!!.add(name)
    }

    val dataTypeField = messages.variablesCsvColumnName(VARIABLE_CSV_COLUMN_INDEX_DATA_TYPE)

    val dataTypeName = values[VARIABLE_CSV_COLUMN_INDEX_DATA_TYPE]
    if (dataTypeName.isNullOrEmpty()) {
      addError(dataTypeField, "", messages.variablesCsvDataTypeRequired())
      return
    }

    val variableType =
        try {
          AllVariableCsvVariableType.create(dataTypeName)
        } catch (e: IllegalArgumentException) {
          addError(dataTypeField, dataTypeName, e.localizedMessage)
          return
        }

    when (variableType) {
      AllVariableCsvVariableType.SingleSelect,
      AllVariableCsvVariableType.MultiSelect ->
          if (values[VARIABLE_CSV_COLUMN_INDEX_OPTIONS].isNullOrEmpty()) {
            addError(dataTypeField, "", messages.variablesCsvDataTypeRequiresOptions())
          }
      else -> Unit
    }

    // Add this as a path because it may be a parent one day!
    val thisPath = "$parentPath\t$name"
    parentPathToChildrenNamesMap[thisPath] = mutableSetOf()
    variableTypeByPath[thisPath] = variableType

    if (parentPath.isNotEmpty()) {
      val parentVariableType =
          variableTypeByPath[parentPath]
              ?: throw IllegalStateException("Unable to find type of parent $parentPath")

      if (parentVariableType != AllVariableCsvVariableType.Table) {
        addError(
            messages.variablesCsvColumnName(VARIABLE_CSV_COLUMN_INDEX_PARENT),
            parent,
            messages.variablesCsvWrongDataTypeForChild())
      }
    }

    validateDeliverableConfiguration(values)
    validateDependencyConfiguration(values)
  }
}
