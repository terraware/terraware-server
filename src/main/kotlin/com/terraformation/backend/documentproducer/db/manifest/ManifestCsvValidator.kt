package com.terraformation.backend.documentproducer.db.manifest

import com.terraformation.backend.documentproducer.db.CsvValidator
import com.terraformation.backend.i18n.Messages

// Column 1/A
const val MANIFEST_CSV_COLUMN_INDEX_NAME = 0
// Column 2/B
const val MANIFEST_CSV_COLUMN_INDEX_STABLE_ID = MANIFEST_CSV_COLUMN_INDEX_NAME + 1
// Column 3/C
const val MANIFEST_CSV_COLUMN_INDEX_DESCRIPTION = MANIFEST_CSV_COLUMN_INDEX_STABLE_ID + 1
// Column 4/D
const val MANIFEST_CSV_COLUMN_INDEX_DATA_TYPE = MANIFEST_CSV_COLUMN_INDEX_DESCRIPTION + 1
// Column 5/E
const val MANIFEST_CSV_COLUMN_INDEX_IS_LIST = MANIFEST_CSV_COLUMN_INDEX_DATA_TYPE + 1
// Column 6/F
const val MANIFEST_CSV_COLUMN_INDEX_RECOMMENDED_VARIABLES = MANIFEST_CSV_COLUMN_INDEX_IS_LIST + 1
// Column 7/G
const val MANIFEST_CSV_COLUMN_INDEX_PARENT = MANIFEST_CSV_COLUMN_INDEX_RECOMMENDED_VARIABLES + 1
// Column 8/H
const val MANIFEST_CSV_COLUMN_INDEX_IS_NON_NUMBERED_SECTION = MANIFEST_CSV_COLUMN_INDEX_PARENT + 1
// Column 9/I
const val MANIFEST_CSV_COLUMN_INDEX_OPTIONS = MANIFEST_CSV_COLUMN_INDEX_IS_NON_NUMBERED_SECTION + 1
// Column 10/J
const val MANIFEST_CSV_COLUMN_INDEX_MIN_VALUE = MANIFEST_CSV_COLUMN_INDEX_OPTIONS + 1
// Column 11/K
const val MANIFEST_CSV_COLUMN_INDEX_MAX_VALUE = MANIFEST_CSV_COLUMN_INDEX_MIN_VALUE + 1
// Column 12/L
const val MANIFEST_CSV_COLUMN_INDEX_DECIMAL_PLACES = MANIFEST_CSV_COLUMN_INDEX_MAX_VALUE + 1
// Column 13/M
const val MANIFEST_CSV_COLUMN_INDEX_TABLE_STYLE = MANIFEST_CSV_COLUMN_INDEX_DECIMAL_PLACES + 1
// Column 14/N
const val MANIFEST_CSV_COLUMN_INDEX_IS_HEADER = MANIFEST_CSV_COLUMN_INDEX_TABLE_STYLE + 1
// Column 15/O
const val MANIFEST_CSV_COLUMN_INDEX_NOTES = MANIFEST_CSV_COLUMN_INDEX_IS_HEADER + 1

class ManifestCsvValidator(
    messages: Messages,
) : CsvValidator(messages) {
  private val existingStableIds = mutableSetOf<String>()

  private val parentPathToChildrenNamesMap = mutableMapOf<String, MutableSet<String>>()
  private val variableTypeByPath = mutableMapOf<String, CsvVariableType>()

  override val validators: List<((String?, String) -> Unit)?> =
      listOf(
          this::validateName,
          this::validateStableId,
          null,
          null,
          null,
          this::validateRecommendedVariables,
          null,
          null,
          this::validateOptions,
          null,
          null,
          null,
          null,
          null,
          null,
      )

  override val rowValidators: List<((List<String?>) -> Unit)> = listOf(this::validateRow)

  override fun getColumnName(position: Int): String {
    return messages.manifestCsvColumnName(position)
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

  private fun validateRecommendedVariables(value: String?, field: String) {
    if (value.isNullOrEmpty()) {
      return
    }

    val recommendations = splitAndTrimNewline(value).map { scrubHyphenPrefix(it) }
    recommendations
        .groupBy { it }
        .filterValues { it.size > 1 }
        .keys
        .forEach { recommendation ->
          addError(field, recommendation, messages.variablesCsvRecommendationNotUnique())
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
    val name = values[MANIFEST_CSV_COLUMN_INDEX_NAME]?.trim()
    if (name.isNullOrEmpty()) {
      // Error was already added in validateName()
      return
    }

    val parent = values[MANIFEST_CSV_COLUMN_INDEX_PARENT]

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
          messages.manifestCsvColumnName(MANIFEST_CSV_COLUMN_INDEX_PARENT),
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

      addError(messages.manifestCsvColumnName(MANIFEST_CSV_COLUMN_INDEX_NAME), name, message)
      return
    } else {
      // Append this as another child to this parent path
      parentPathToChildrenNamesMap[parentPath]!!.add(name)
    }

    val dataTypeField = messages.manifestCsvColumnName(MANIFEST_CSV_COLUMN_INDEX_DATA_TYPE)

    val dataTypeName = values[MANIFEST_CSV_COLUMN_INDEX_DATA_TYPE]
    if (dataTypeName.isNullOrEmpty()) {
      addError(dataTypeField, "", messages.variablesCsvDataTypeRequired())
      return
    }

    val variableType =
        try {
          CsvVariableType.create(dataTypeName)
        } catch (e: IllegalArgumentException) {
          addError(dataTypeField, dataTypeName, e.localizedMessage)
          return
        }

    when (variableType) {
      CsvVariableType.SingleSelect,
      CsvVariableType.MultiSelect ->
          if (values[MANIFEST_CSV_COLUMN_INDEX_OPTIONS].isNullOrEmpty()) {
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

      if (variableType == CsvVariableType.Section) {
        if (parentVariableType != CsvVariableType.Section) {
          addError(
              messages.manifestCsvColumnName(MANIFEST_CSV_COLUMN_INDEX_PARENT),
              parent,
              messages.variablesCsvSectionParentMustBeSection())
        }
      } else if (parentVariableType != CsvVariableType.Table) {
        addError(
            messages.manifestCsvColumnName(MANIFEST_CSV_COLUMN_INDEX_PARENT),
            parent,
            messages.variablesCsvWrongDataTypeForChild())
      }
    }
  }
}
