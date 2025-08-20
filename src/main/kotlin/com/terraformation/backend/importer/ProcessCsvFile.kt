package com.terraformation.backend.importer

import com.opencsv.CSVReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Synchronously processes a CSV file. Typically this will be for admin functions where it's okay
 * for an upload request to take an arbitrary amount of time to finish. For CSV uploads from end
 * users, use [CsvImporter].
 *
 * @param maxErrors Abort processing after this many errors have been added. This should be high
 *   enough that users won't feel like they have to keep retrying the file after fixing each problem
 *   but low enough that a file where every row has a problem won't result in a uselessly huge list
 *   of errors.
 * @throws CsvImportFailedException Errors were reported during processing of the file.
 */
fun processCsvFile(
    inputStream: InputStream,
    skipHeaderRow: Boolean = true,
    exceptionMessage: String = "CSV processing failed",
    maxErrors: Int = 50,
    importRow: ImportCsvRow,
) {
  val errors = mutableListOf<CsvImportError>()
  var rowNumber = 0

  val csvReader = CSVReader(InputStreamReader(inputStream))

  if (skipHeaderRow) {
    if (csvReader.readNext() == null) {
      throw CsvImportFailedException(
          listOf(CsvImportError(0, "No data found in file")),
          exceptionMessage,
      )
    }

    rowNumber++
  }

  csvReader.forEach { rawValues ->
    val values = rawValues.map { it?.trim()?.ifBlank { null } }

    rowNumber++

    importRow.importRow(values, rowNumber) { message ->
      errors.add(CsvImportError(rowNumber, message))
      if (errors.size >= maxErrors) {
        throw CsvImportFailedException(errors, exceptionMessage)
      }
    }
  }

  if (errors.isNotEmpty()) {
    throw CsvImportFailedException(errors, exceptionMessage)
  }
}

fun interface ImportCsvRow {
  /**
   * Imports a row from the CSV file. This is the callback function for [processCsvFile].
   *
   * @param values Trimmed values of the columns. Blank values are null.
   * @param addError Callback to add an error to the list of errors.
   */
  fun importRow(values: List<String?>, rowNumber: Int, addError: (String) -> Unit)
}

data class CsvImportError(val rowNumber: Int, val message: String)

class CsvImportFailedException(val errors: List<CsvImportError>, message: String) :
    RuntimeException(message)
