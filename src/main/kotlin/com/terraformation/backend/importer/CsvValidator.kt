package com.terraformation.backend.importer

import com.opencsv.CSVReader
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.tables.pojos.UploadProblemsRow
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.species.model.validateScientificNameSyntax
import java.io.InputStream
import java.io.InputStreamReader
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeParseException

abstract class CsvValidator(
    protected val uploadId: UploadId,
    protected val messages: Messages,
) {
  /**
   * List of column validation functions that take two arguments: the value (which is null if the
   * column was blank in the CSV) and the column name. If a column is freeform text, its validation
   * function should be null.
   */
  abstract val validators: List<((String?, String) -> Unit)?>

  /** List of validation functions that operate on entire rows. */
  open val rowValidators: List<(List<String?>) -> Unit> = emptyList()

  /** Returns the name of a column (zero-indexed) in the current locale. */
  abstract fun getColumnName(position: Int): String

  val warnings = mutableListOf<UploadProblemsRow>()
  val errors = mutableListOf<UploadProblemsRow>()

  protected var rowNum = 1

  protected val decimalFormat = NumberFormat.getNumberInstance(currentLocale())!!

  fun validate(inputStream: InputStream) {
    val csvReader = CSVReader(InputStreamReader(inputStream))

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
      addError(UploadProblemType.MissingRequiredValue, null, null, messages.csvBadHeader())
      false
    } else if (rawValues.size != validators.size) {
      addError(UploadProblemType.MalformedValue, null, null, messages.csvBadHeader())
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
      addError(
          UploadProblemType.MalformedValue,
          null,
          null,
          messages.csvWrongFieldCount(validators.size, rawValues.size),
      )
      return
    }

    val values = rawValues.map { it?.trim()?.ifEmpty { null } }

    if (shouldValidateRow(values)) {
      validators.forEachIndexed { index, validator ->
        if (validator != null) {
          validator(values[index], getColumnName(index))
        }
      }

      rowValidators.forEach { validator -> validator(values) }
    }
  }

  protected fun addError(
      type: UploadProblemType,
      field: String?,
      value: String?,
      message: String,
      position: Int? = rowNum,
  ) {
    errors +=
        UploadProblemsRow(
            isError = true,
            field = field,
            message = message,
            position = position,
            typeId = type,
            uploadId = uploadId,
            value = value,
        )
  }

  protected fun addWarning(
      type: UploadProblemType,
      field: String?,
      value: String?,
      message: String,
      position: Int? = rowNum,
  ) {
    warnings +=
        UploadProblemsRow(
            isError = false,
            field = field,
            message = message,
            position = position,
            typeId = type,
            uploadId = uploadId,
            value = value,
        )
  }

  protected fun validateDate(value: String?, field: String) {
    if (value == null) {
      addError(
          UploadProblemType.MissingRequiredValue,
          field,
          null,
          messages.csvRequiredFieldMissing(),
      )
    } else {
      try {
        LocalDate.parse(value)
      } catch (e: DateTimeParseException) {
        addError(UploadProblemType.MalformedValue, field, value, messages.csvDateMalformed())
      }
    }
  }

  protected fun validateScientificName(value: String?, field: String) {
    if (value == null) {
      addError(
          UploadProblemType.MissingRequiredValue,
          field,
          null,
          messages.csvScientificNameMissing(),
      )
    } else {
      validateScientificNameSyntax(
          value,
          onTooShort = {
            addError(
                UploadProblemType.MalformedValue,
                field,
                value,
                messages.csvScientificNameTooShort(),
            )
          },
          onTooLong = {
            addError(
                UploadProblemType.MalformedValue,
                field,
                value,
                messages.csvScientificNameTooLong(),
            )
          },
          onInvalidCharacter = { invalidChar ->
            addError(
                UploadProblemType.MalformedValue,
                field,
                value,
                messages.csvScientificNameInvalidChar(invalidChar),
            )
          },
      )
    }
  }
}
