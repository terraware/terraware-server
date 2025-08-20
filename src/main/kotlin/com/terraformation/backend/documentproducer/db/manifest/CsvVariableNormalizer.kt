package com.terraformation.backend.documentproducer.db.manifest

import com.opencsv.CSVReader
import com.terraformation.backend.db.StableId
import java.io.InputStreamReader

class CsvVariableNormalizer {
  private val variablePaths: MutableList<String> = mutableListOf()

  fun normalizeFromCsv(inputBytes: ByteArray): List<CsvSectionVariable> {
    val csvReader = CSVReader(InputStreamReader(inputBytes.inputStream()))
    // Consume the header, it was already validated
    csvReader.readNext()

    return csvReader.mapIndexed { index, rawValues ->
      val values = rawValues.map { it.ifEmpty { null } }

      val name = rawValues[MANIFEST_CSV_COLUMN_INDEX_NAME].trim()
      val parent = values[MANIFEST_CSV_COLUMN_INDEX_PARENT]?.trim()

      CsvSectionVariable(
          defaultSectionText = values[MANIFEST_CSV_COLUMN_INDEX_DEFAULT_SECTION_TEXT],
          description = values[MANIFEST_CSV_COLUMN_INDEX_DESCRIPTION],
          isNonNumberedSection =
              normalizeBoolean(values[MANIFEST_CSV_COLUMN_INDEX_IS_NON_NUMBERED_SECTION]),
          name = name,
          parent = parent,
          parentPath = getParentPath(parent),
          position = index + 2,
          recommendedVariables =
              splitAndTrimNewline(values[MANIFEST_CSV_COLUMN_INDEX_RECOMMENDED_VARIABLES]).map {
                scrubHyphenPrefix(it)
              },
          stableId = StableId(rawValues[MANIFEST_CSV_COLUMN_INDEX_STABLE_ID]),
          variablePath = getVariablePath(name, parent),
      )
    }
  }

  private fun normalizeBoolean(input: String?): Boolean = input?.lowercase() in setOf("yes", "true")

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
