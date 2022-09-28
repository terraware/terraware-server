package com.terraformation.backend.seedbank.db

import com.opencsv.CSVReader
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.tables.pojos.UploadProblemsRow
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.seedbank.model.isV2Compatible
import com.terraformation.backend.species.model.validateScientificNameSyntax
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.format.DateTimeParseException

class AccessionCsvValidator(
    private val uploadId: UploadId,
    private val messages: Messages,
    private val countryCodesByLowerCsvValue: Map<String, String>,
    private val findExistingAccessionNumbers: (Collection<String>) -> Collection<String>,
) {
  val warnings = mutableListOf<UploadProblemsRow>()
  val errors = mutableListOf<UploadProblemsRow>()

  private var rowNum = 1
  private val accessionNumberToRow = mutableMapOf<String, Int>()

  companion object {
    private val validStates =
        AccessionState.values().filter { it.isV2Compatible }.map { it.displayName }.toSet()
    private val validUnits = SeedQuantityUnits.values().map { it.displayName }.toSet()
  }

  fun validate(inputStream: InputStream) {
    val csvReader = CSVReader(InputStreamReader(inputStream))

    validateHeaderRow(csvReader.readNext())

    csvReader.forEach { rawValues ->
      rowNum++
      validateRow(rawValues)
    }

    checkExistingAccessionNumbers()
  }

  private fun checkExistingAccessionNumbers() {
    if (accessionNumberToRow.isNotEmpty()) {
      val existingNumbers = findExistingAccessionNumbers(accessionNumberToRow.keys)
      existingNumbers.forEach { existingNumber ->
        val existingRowNum = accessionNumberToRow[existingNumber]
        if (existingRowNum != null) {
          addWarning(
              UploadProblemType.DuplicateValue,
              ACCESSION_CSV_HEADERS[0],
              existingNumber,
              messages.accessionCsvNumberExists(),
              existingRowNum)
        }
      }
    }
  }

  private fun validateHeaderRow(rawValues: Array<String?>?) {
    if (rawValues == null) {
      addError(UploadProblemType.MissingRequiredValue, null, null, messages.csvBadHeader())
    } else if (rawValues.size != ACCESSION_CSV_HEADERS.size) {
      addError(
          UploadProblemType.MalformedValue,
          null,
          null,
          messages.csvWrongFieldCount(ACCESSION_CSV_HEADERS.size, rawValues.size))
    }
  }

  private fun validateRow(rawValues: Array<String?>) {
    if (rawValues.size != ACCESSION_CSV_HEADERS.size) {
      addError(
          UploadProblemType.MalformedValue,
          null,
          null,
          messages.csvWrongFieldCount(ACCESSION_CSV_HEADERS.size, rawValues.size))
      return
    }

    val values = rawValues.map { it?.trim()?.ifEmpty { null } }

    // Our example template file has a zillion rows where only the status and collection source
    // columns have values; we don't want to flag those rows as errors if the user downloads the
    // template, edits some of the rows, and leaves the other example rows in place.
    val columnsWithValues = values.count { it != null }
    if (columnsWithValues == 0 ||
        (values[5] != null && values[14] != null && columnsWithValues == 2)) {
      return
    }

    validateAccessionNumber(values[0], ACCESSION_CSV_HEADERS[0])
    validateScientificName(values[1], ACCESSION_CSV_HEADERS[1])
    validateQuantity(values[3], ACCESSION_CSV_HEADERS[3])
    validateUnits(values[4], ACCESSION_CSV_HEADERS[4])
    validateStatus(values[5], ACCESSION_CSV_HEADERS[5])
    validateCollectionDate(values[6], ACCESSION_CSV_HEADERS[6])
    validateCountryCode(values[11], ACCESSION_CSV_HEADERS[11])
    validateCollectionSource(values[14], ACCESSION_CSV_HEADERS[14])
    validateNumberOfPlants(values[15], ACCESSION_CSV_HEADERS[15])
  }

  private fun validateAccessionNumber(value: String?, field: String) {
    if (value != null) {
      val existingRow = accessionNumberToRow[value]
      if (existingRow != null) {
        addError(
            UploadProblemType.DuplicateValue,
            field,
            value,
            messages.accessionCsvNumberDuplicate(existingRow))
      } else {
        accessionNumberToRow[value] = rowNum
      }
    }
  }

  private fun validateScientificName(value: String?, field: String) {
    if (value == null) {
      addError(
          UploadProblemType.MissingRequiredValue, field, null, messages.csvScientificNameMissing())
    } else {
      validateScientificNameSyntax(
          value,
          onTooShort = {
            addError(
                UploadProblemType.MalformedValue,
                field,
                value,
                messages.csvScientificNameTooShort())
          },
          onTooLong = {
            addError(
                UploadProblemType.MalformedValue, field, value, messages.csvScientificNameTooLong())
          },
          onInvalidCharacter = { invalidChar ->
            addError(
                UploadProblemType.MalformedValue,
                field,
                value,
                messages.csvScientificNameInvalidChar(invalidChar))
          })
    }
  }

  private fun validateQuantity(value: String?, field: String) {
    if (value != null) {
      val floatValue = value.toFloatOrNull()
      if (floatValue == null || floatValue < 0) {
        addError(
            UploadProblemType.MalformedValue, field, value, messages.accessionCsvQuantityInvalid())
      }
    }
  }

  private fun validateUnits(value: String?, field: String) {
    if (value != null && value !in validUnits) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.accessionCsvQuantityUnitsInvalid())
    }
  }

  private fun validateCountryCode(value: String?, field: String) {
    if (value != null && value.lowercase() !in countryCodesByLowerCsvValue) {
      addError(
          UploadProblemType.UnrecognizedValue, field, value, messages.accessionCsvCountryInvalid())
    }
  }

  private fun validateStatus(value: String?, field: String) {
    if (value != null && value !in validStates) {
      addError(
          UploadProblemType.UnrecognizedValue, field, value, messages.accessionCsvStatusInvalid())
    }
  }

  private fun validateNumberOfPlants(value: String?, field: String) {
    if (value != null) {
      val intValue = value.toIntOrNull()
      if (intValue == null || intValue < 0) {
        addError(
            UploadProblemType.MalformedValue,
            field,
            value,
            messages.accessionCsvNumberOfPlantsInvalid())
      }
    }
  }

  private fun validateCollectionSource(value: String?, field: String) {
    if (value != null && value.toCollectionSource() == null) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.accessionCsvCollectionSourceInvalid())
    }
  }

  private fun validateCollectionDate(value: String?, field: String) {
    if (value == null) {
      addError(
          UploadProblemType.MissingRequiredValue, field, null, messages.csvRequiredFieldMissing())
    } else {
      try {
        LocalDate.parse(value)
      } catch (e: DateTimeParseException) {
        addError(UploadProblemType.MalformedValue, field, value, messages.csvDateMalformed())
      }
    }
  }

  private fun addError(type: UploadProblemType, field: String?, value: String?, message: String) {
    errors +=
        UploadProblemsRow(
            isError = true,
            field = field,
            message = message,
            position = rowNum,
            typeId = type,
            uploadId = uploadId,
            value = value,
        )
  }

  private fun addWarning(
      type: UploadProblemType,
      field: String?,
      value: String?,
      message: String,
      row: Int = rowNum
  ) {
    warnings +=
        UploadProblemsRow(
            isError = false,
            field = field,
            message = message,
            position = row,
            typeId = type,
            uploadId = uploadId,
            value = value,
        )
  }
}

/**
 * In the template, the collection source names have additional explanatory suffixes such as "(In
 * Situ)". We want to accept but not require those suffixes.
 */
fun String.toCollectionSource(): CollectionSource? {
  return CollectionSource.values().firstOrNull { startsWith(it.displayName, ignoreCase = true) }
}

private val ACCESSION_CSV_HEADERS =
    arrayOf(
        "Accession Number",
        "Species (Scientific Name)",
        "Species (Common Name)",
        "QTY",
        "QTY Units",
        "Status",
        "Collection Date",
        "Collecting Site Name",
        "Landowner",
        "City or County",
        "State / Province / Region",
        "Country",
        "Site Description / Notes",
        "Collector Name",
        "Collection Source",
        "Number of Plants",
        "Plant ID",
    )
