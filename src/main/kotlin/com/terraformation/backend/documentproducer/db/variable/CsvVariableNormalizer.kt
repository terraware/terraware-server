package com.terraformation.backend.documentproducer.db.variable

import com.opencsv.CSVReader
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.docprod.DependencyCondition
import com.terraformation.backend.db.docprod.VariableTableStyle
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectOptionsRow
import java.io.InputStreamReader
import java.math.BigDecimal

class CsvVariableNormalizer {
  private val variablePaths: MutableList<String> = mutableListOf()

  fun normalizeFromCsv(inputBytes: ByteArray): List<AllVariableCsvVariable> {
    val csvReader = CSVReader(InputStreamReader(inputBytes.inputStream()))
    // Consume the header, it was already validated
    csvReader.readNext()

    return csvReader.mapIndexed { index, rawValues ->
      val values = rawValues.map { it.ifEmpty { null } }

      val dataType =
          AllVariableCsvVariableType.create(rawValues[VARIABLE_CSV_COLUMN_INDEX_DATA_TYPE])
      val isList =
          normalizeBoolean(
              values[VARIABLE_CSV_COLUMN_INDEX_IS_LIST],
              defaultValue = dataType == AllVariableCsvVariableType.Table,
          )
      val name = rawValues[VARIABLE_CSV_COLUMN_INDEX_NAME].trim()

      val parent = values[VARIABLE_CSV_COLUMN_INDEX_PARENT]?.trim()
      val parentPath = getParentPath(parent)
      val variablePath = getVariablePath(name, parent)

      AllVariableCsvVariable(
          name = name,
          stableId = StableId(rawValues[VARIABLE_CSV_COLUMN_INDEX_STABLE_ID]),
          description = values[VARIABLE_CSV_COLUMN_INDEX_DESCRIPTION],
          dataType = dataType,
          isList = isList,
          parent = parent,
          options =
              splitAndTrimNewline(values[VARIABLE_CSV_COLUMN_INDEX_OPTIONS]).mapIndexed {
                  optionIndex,
                  rawOptionName ->
                mapOptionStringToSelectOptionRow(rawOptionName, optionIndex + 1)
              },
          minValue = normalizeFloat(values[VARIABLE_CSV_COLUMN_INDEX_MIN_VALUE]),
          maxValue = normalizeFloat(values[VARIABLE_CSV_COLUMN_INDEX_MAX_VALUE]),
          decimalPlaces = normalizeNumber(values[VARIABLE_CSV_COLUMN_INDEX_DECIMAL_PLACES]),
          tableStyle =
              if (values[VARIABLE_CSV_COLUMN_INDEX_TABLE_STYLE]?.lowercase() == "vertical")
                  VariableTableStyle.Vertical
              else VariableTableStyle.Horizontal,
          isHeader = normalizeBoolean(values[VARIABLE_CSV_COLUMN_INDEX_IS_HEADER]),
          notes = values[VARIABLE_CSV_COLUMN_INDEX_NOTES],
          deliverableQuestion = values[VARIABLE_CSV_COLUMN_INDEX_DELIVERABLE_QUESTION],
          dependencyVariableStableId =
              values[VARIABLE_CSV_COLUMN_INDEX_DEPENDENCY_VARIABLE_STABLE_ID]?.let { StableId(it) },
          dependencyCondition =
              values[VARIABLE_CSV_COLUMN_INDEX_DEPENDENCY_CONDITION]?.let {
                when (it.lowercase()) {
                  "=" -> DependencyCondition.Eq
                  "eq" -> DependencyCondition.Eq
                  "<" -> DependencyCondition.Lt
                  "lt" -> DependencyCondition.Lt
                  "<=" -> DependencyCondition.Lte
                  "lte" -> DependencyCondition.Lte
                  ">" -> DependencyCondition.Gt
                  "gt" -> DependencyCondition.Gt
                  ">=" -> DependencyCondition.Gte
                  "gte" -> DependencyCondition.Gte
                  "!=" -> DependencyCondition.Neq
                  "neq" -> DependencyCondition.Neq
                  else ->
                      throw IllegalArgumentException("Invalid Dependency Condition supplied - $it")
                }
              },
          dependencyValue = values[VARIABLE_CSV_COLUMN_INDEX_DEPENDENCY_VALUE],
          internalOnly = normalizeBoolean(values[VARIABLE_CSV_COLUMN_INDEX_INTERNAL_ONLY]),
          isRequired = normalizeBoolean(values[VARIABLE_CSV_COLUMN_INDEX_IS_REQUIRED]),
          position = index + 2,
          variablePath = variablePath,
          parentPath = parentPath,
      )
    }
  }

  private fun normalizeNumber(input: String?): Int? = input?.toIntOrNull()

  private fun normalizeFloat(input: String?): BigDecimal? = input?.toBigDecimalOrNull()

  private fun normalizeBoolean(input: String?, defaultValue: Boolean = false): Boolean {
    return when (input?.lowercase()) {
      in setOf("yes", "true") -> true
      in setOf("no", "false") -> false
      else -> defaultValue
    }
  }

  private val renderedTextRegex = """\[\[(?<renderedText>.*)]]""".toRegex()

  private fun getOptionName(rawOptionName: String): String =
      scrubHyphenPrefix(rawOptionName).replace(renderedTextRegex, "").trim()

  private fun getRenderedTextFromSelectOptionString(rawOptionName: String): String? {
    val matchReader = renderedTextRegex.find(rawOptionName)
    return matchReader?.groups?.get("renderedText")?.value?.trim()
  }

  private fun mapOptionStringToSelectOptionRow(
      rawOptionName: String,
      position: Int,
  ): VariableSelectOptionsRow =
      VariableSelectOptionsRow(
          name = getOptionName(rawOptionName),
          position = position,
          renderedText = getRenderedTextFromSelectOptionString(rawOptionName),
          variableTypeId = VariableType.Select,
      )

  private fun getVariablePath(name: String, parent: String?): String {
    // If there is no parent, this is top level, the path is equal to the name
    if (parent.isNullOrEmpty()) {
      val path = "\t$name"
      variablePaths.add(path)
      return path
    }

    val parentPath =
        getParentPath(parent)
            // This should be impossible as long as we are getting the paths sequentially, IE a
            // child is never normalized before the parent
            ?: throw IllegalStateException("Unable to determine path for parent $parent")

    val thisPath = "$parentPath\t$name"
    variablePaths.add(thisPath)
    return thisPath
  }

  private fun getParentPath(parent: String?): String? =
      parent?.let { variablePaths.findLast { it.endsWith("\t$parent") } }
}

fun splitAndTrimNewline(input: String?): List<String> =
    input?.lines()?.map { it.trim() } ?: emptyList()

private val hyphenPrefixRegex = """^- ?""".toRegex()

fun scrubHyphenPrefix(input: String): String = input.replace(hyphenPrefixRegex, "")
