package com.terraformation.backend.util

import com.opencsv.CSVParser
import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReader
import com.opencsv.CSVReaderBuilder
import com.opencsv.enums.CSVReaderNullFieldIndicator
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/**
 * Wrapper around [CSVReader] that runs custom logic to map CSV rows to objects.
 *
 * The built-in support for object mapping in [CSVReader] makes heavy use of reflection and is slow
 * enough to be a significant bottleneck when importing large data sets. This class is less
 * flexible, but significantly faster.
 */
abstract class ParsedCsvReader<T>(private val csvReader: CSVReader) {
  constructor(
      reader: Reader,
      csvParser: CSVParser,
  ) : this(CSVReaderBuilder(reader).withCSVParser(csvParser).build())

  constructor(
      inputStream: InputStream,
      csvParser: CSVParser,
  ) : this(InputStreamReader(inputStream), csvParser)

  private lateinit var positions: Map<String, Int>
  private var numColumns: Int = -1

  private fun readHeaderRow() {
    if (numColumns >= 0) {
      throw IllegalStateException("This instance has already been used and cannot be reused.")
    }

    val headerRow =
        csvReader.readNext() ?: throw IllegalArgumentException("Could not read header row")
    numColumns = headerRow.size
    positions = headerRow.mapIndexed { index, columnName -> columnName!! to index }.toMap()
  }

  /**
   * Returns the value of the column of a particular name from this row. Column names are defined by
   * the first row (the header row) of the input.
   */
  protected operator fun Array<String?>.get(columnName: String): String? {
    val pos =
        positions[columnName]
            ?: throw IllegalArgumentException("Column $columnName not found in input file")

    return get(pos)
  }

  /**
   * Returns a sequence of parsed values from the input. The first row must be a header row
   * containing the names of the columns.
   *
   * The sequence is lazily loaded from the input; this does not read the entire input into memory.
   */
  fun sequence(): Sequence<T> {
    readHeaderRow()
    return csvReader.asSequence().filter { it.size == numColumns }.mapNotNull { parseRow(it) }
  }

  /**
   * Converts an array of string field values to the appropriate data type. Empty columns in the
   * input are represented as nulls in the array. The array is guaranteed to have the same number of
   * columns as the header row.
   *
   * @param row The values from the current row. Empty columns are represented as nulls. Values may
   *   be looked up using column names, e.g., `row["taxonID"]`, thanks to the [get] extension
   *   method.
   */
  abstract fun parseRow(row: Array<String?>): T?

  companion object {
    /**
     * Returns a [CSVParser] configured to parse GBIF TSV files. The parser is stateful, so we
     * construct a new one for each input stream.
     */
    fun tsvParser(): CSVParser =
        CSVParserBuilder()
            .withSeparator('\t')
            .withIgnoreQuotations(true)
            .withFieldAsNull(CSVReaderNullFieldIndicator.BOTH)
            .build()
  }
}
