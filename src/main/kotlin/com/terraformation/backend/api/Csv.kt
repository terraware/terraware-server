package com.terraformation.backend.api

import com.opencsv.CSVWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * Constructs an HTTP response in CSV format.
 *
 * @param filename Filename that will be used by default if the user saves the CSV file. If null,
 *   response will not include a filename.
 * @param columnNames Contents of the first row of the CSV; this should be a list of the names of
 *   the columns, typically in human-readable form.
 * @param writeRows Callback function that writes the data rows to the CSV stream. This should call
 *   [CSVWriter.writeNext] for each data row.
 */
fun csvResponse(
    filename: String?,
    columnNames: List<String>,
    writeRows: (CSVWriter) -> Unit,
): ResponseEntity<ByteArray> {
  val byteArrayOutputStream = ByteArrayOutputStream()

  // Write a UTF-8 BOM so Excel won't screw up the character encoding if there are non-ASCII
  // characters.
  byteArrayOutputStream.write(239)
  byteArrayOutputStream.write(187)
  byteArrayOutputStream.write(191)

  CSVWriter(OutputStreamWriter(byteArrayOutputStream, StandardCharsets.UTF_8)).use { csvWriter ->
    csvWriter.writeNext(columnNames)
    writeRows(csvWriter)
  }

  val value = byteArrayOutputStream.toByteArray()
  val headers = HttpHeaders()
  headers.contentLength = value.size.toLong()
  headers["Content-type"] = "text/csv;charset=UTF-8"

  if (filename != null) {
    headers.contentDisposition = ContentDisposition.attachment().filename(filename).build()
  }

  return ResponseEntity(value, headers, HttpStatus.OK)
}

/**
 * Writes a row of data to a CSV writer. This is a convenience wrapper that allows the caller to
 * supply the values as `List<Any?>` rather than `Array<String?>`.
 */
fun CSVWriter.writeNext(nextLine: List<Any?>, applyQuotesToAll: Boolean = false) {
  val stringValues = nextLine.map { it?.toString() }.toTypedArray()
  writeNext(stringValues, applyQuotesToAll)
}
