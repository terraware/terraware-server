package com.terraformation.backend.documentproducer.db.manifest

import com.opencsv.CSVReader
import com.terraformation.backend.db.docprod.VariableTableStyle
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectOptionsRow
import java.io.InputStreamReader
import java.math.BigDecimal

class CsvVariableNormalizer {
  private val variablePaths: MutableList<String> = mutableListOf()

  fun normalizeFromCsv(inputBytes: ByteArray): List<CsvVariable> {
    val csvReader = CSVReader(InputStreamReader(inputBytes.inputStream()))
    // Consume the header, it was already validated
    csvReader.readNext()

    return csvReader.mapIndexed { index, rawValues ->
      val values = rawValues.map { it.ifEmpty { null } }
      val dataType = CsvVariableType.create(rawValues[MANIFEST_CSV_COLUMN_INDEX_DATA_TYPE])

      // Sections are always lists.
      val isList =
          dataType == CsvVariableType.Section ||
              normalizeBoolean(values[MANIFEST_CSV_COLUMN_INDEX_IS_LIST])

      val name = rawValues[MANIFEST_CSV_COLUMN_INDEX_NAME].trim()
      val parent = values[MANIFEST_CSV_COLUMN_INDEX_PARENT]?.trim()

      CsvVariable(
          name = name,
          stableId = rawValues[MANIFEST_CSV_COLUMN_INDEX_STABLE_ID],
          description = values[MANIFEST_CSV_COLUMN_INDEX_DESCRIPTION],
          dataType = dataType,
          isList = isList,
          recommendedVariables =
              splitAndTrimNewline(values[MANIFEST_CSV_COLUMN_INDEX_RECOMMENDED_VARIABLES]).map {
                scrubHyphenPrefix(it)
              },
          parent = parent,
          isNonNumberedSection =
              normalizeBoolean(values[MANIFEST_CSV_COLUMN_INDEX_IS_NON_NUMBERED_SECTION]),
          options =
              splitAndTrimNewline(values[MANIFEST_CSV_COLUMN_INDEX_OPTIONS]).mapIndexed {
                  optionIndex,
                  rawOptionName ->
                mapOptionStringToSelectOptionRow(rawOptionName, optionIndex + 1)
              },
          minValue = normalizeFloat(values[MANIFEST_CSV_COLUMN_INDEX_MIN_VALUE]),
          maxValue = normalizeFloat(values[MANIFEST_CSV_COLUMN_INDEX_MAX_VALUE]),
          decimalPlaces = normalizeNumber(values[MANIFEST_CSV_COLUMN_INDEX_DECIMAL_PLACES]),
          tableStyle =
              if (values[MANIFEST_CSV_COLUMN_INDEX_TABLE_STYLE]?.lowercase() == "vertical")
                  VariableTableStyle.Vertical
              else VariableTableStyle.Horizontal,
          isHeader = normalizeBoolean(values[MANIFEST_CSV_COLUMN_INDEX_IS_HEADER]),
          notes = values[MANIFEST_CSV_COLUMN_INDEX_NOTES],
          position = index + 2,
          variablePath = getVariablePath(name, parent),
          parentPath = getParentPath(parent))
    }
  }

  private fun normalizeNumber(input: String?): Int? = input?.toIntOrNull()

  private fun normalizeFloat(input: String?): BigDecimal? = input?.toBigDecimalOrNull()

  private fun normalizeBoolean(input: String?): Boolean = input?.lowercase() in setOf("yes", "true")

  private val renderedTextRegex = """\[\[(?<renderedText>.*)]]""".toRegex()

  private fun getOptionName(rawOptionName: String): String =
      scrubHyphenPrefix(rawOptionName).replace(renderedTextRegex, "").trim()

  private fun getRenderedTextFromSelectOptionString(rawOptionName: String): String? {
    val matchReader = renderedTextRegex.find(rawOptionName)
    return matchReader?.groups?.get("renderedText")?.value?.trim()
  }

  private fun mapOptionStringToSelectOptionRow(
      rawOptionName: String,
      position: Int
  ): VariableSelectOptionsRow =
      VariableSelectOptionsRow(
          name = getOptionName(rawOptionName),
          position = position,
          renderedText = getRenderedTextFromSelectOptionString(rawOptionName),
          variableTypeId = VariableType.Select)

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
