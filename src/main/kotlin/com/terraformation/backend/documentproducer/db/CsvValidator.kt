package com.terraformation.backend.document.db

import com.opencsv.CSVReader
import com.terraformation.pdd.i18n.Messages
import com.terraformation.pdd.i18n.currentLocale
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeParseException

abstract class CsvValidator(
    protected val messages: Messages,
) {
  /**
   * List of column validation functions that take two arguments: the value (which is null if the
   * column was blank in the CSV) and the column name. If a column is freeform text, its validation
   * function should be null.
   */
  abstract val validators: List<((String?, String) -> Unit)?>

  /** List of validation functions that operate on entire rows. */
  open val rowValidators: List<((List<String?>) -> Unit)> = emptyList()

  /** Returns the name of a column (zero-indexed) in the current locale. */
  abstract fun getColumnName(position: Int): String

  val warnings = mutableListOf<String>()
  val errors = mutableListOf<String>()

  protected var rowNum = 1

  protected val decimalFormat = NumberFormat.getNumberInstance(currentLocale())!!

  fun validate(inputBytes: ByteArray) {
    validate(ByteArrayInputStream(inputBytes))
  }

  fun validate(inputStream: InputStream) {
    validate(InputStreamReader(inputStream))
  }

  fun validate(reader: Reader) {
    val csvReader = CSVReader(reader)

    if (validateHeaderRow(csvReader.readNext())) {
      csvReader.forEach { rawValues ->
        rowNum++
        validateRow(rawValues)
      }

      if (errors.isEmpty()) {
        checkFilePostConditions()
      }
    }
  }

  /**
   * Validates that the header row exists and has the expected number of columns. Override this if
   * you need to do additional validation.
   *
   * @return true if the header row was valid.
   */
  protected open fun validateHeaderRow(rawValues: Array<String?>?): Boolean {
    return if (rawValues == null) {
      addError(null, null, messages.csvBadHeader())
      false
    } else if (rawValues.size != validators.size) {
      addError(null, null, messages.csvBadHeader())
      false
    } else {
      true
    }
  }

  /**
   * Returns true if a row's column values should be validated. Override this if certain rows should
   * be skipped, e.g., rows with example values from a template.
   */
  protected open fun shouldValidateRow(values: List<String?>): Boolean = true

  /**
   * Performs custom checks after the entire file has been validated. Override this if you need to
   * do things like check for duplicate values. This is only called if the file has no errors.
   */
  protected open fun checkFilePostConditions() {}

  private fun validateRow(rawValues: Array<String?>) {
    if (rawValues.size != validators.size) {
      addError(null, null, messages.csvWrongFieldCount(validators.size, rawValues.size))
      return
    }

    val values = rawValues.map { it?.trim()?.ifEmpty { null } }

    if (shouldValidateRow(values)) {
      validators.forEachIndexed { index, validator ->
        if (validator != null) {
          validator(values[index], getColumnName(index))
        }
      }

      rowValidators.forEach { validator -> validator.invoke(values) }
    }
  }

  protected fun addError(
      field: String?,
      value: String?,
      message: String,
      position: Int? = rowNum,
  ) {
    errors += "Message: $message, Field: $field, Value: $value, Position: $position"
  }

  protected fun addWarning(
      field: String?,
      value: String?,
      message: String,
      position: Int? = rowNum,
  ) {
    warnings += "Message: $message, Field: $field, Value: $value, Position: $position"
  }

  protected fun validateDate(value: String?, field: String) {
    if (value == null) {
      addError(field, null, messages.csvRequiredFieldMissing())
    } else {
      try {
        LocalDate.parse(value)
      } catch (e: DateTimeParseException) {
        addError(field, value, messages.csvDateMalformed())
      }
    }
  }
}

data class ErrorsAndMessages(val warnings: List<String>, val errors: List<String>)
