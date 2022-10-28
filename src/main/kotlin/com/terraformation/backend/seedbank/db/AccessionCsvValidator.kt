package com.terraformation.backend.seedbank.db

import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.importer.CsvValidator
import com.terraformation.backend.seedbank.model.isV2Compatible

class AccessionCsvValidator(
    uploadId: UploadId,
    messages: Messages,
    private val countryCodesByLowerCsvValue: Map<String, String>,
    private val findExistingAccessionNumbers: (Collection<String>) -> Collection<String>,
) : CsvValidator(uploadId, messages) {
  companion object {
    private val validStates =
        AccessionState.values().filter { it.isV2Compatible }.map { it.displayName }.toSet()
    private val validUnits = SeedQuantityUnits.values().map { it.displayName }.toSet()
  }

  override val columns: List<Pair<String, ((String?, String) -> Unit)?>> =
      listOf(
          "Accession Number" to this::validateAccessionNumber,
          "Species (Scientific Name)" to this::validateScientificName,
          "Species (Common Name)" to null,
          "QTY" to this::validateQuantity,
          "QTY Units" to this::validateUnits,
          "Status" to this::validateStatus,
          "Collection Date" to this::validateDate,
          "Collecting Site Name" to null,
          "Landowner" to null,
          "City or County" to null,
          "State / Province / Region" to null,
          "Country" to this::validateCountryCode,
          "Site Description / Notes" to null,
          "Collector Name" to null,
          "Collection Source" to this::validateCollectionSource,
          "Number of Plants" to this::validateNumberOfPlants,
          "Plant ID" to null,
      )

  private val accessionNumberToRow = mutableMapOf<String, Int>()

  override fun checkFilePostConditions() {
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

  override fun shouldValidateRow(values: List<String?>): Boolean {
    // Our example template file has a zillion rows where only the status and collection source
    // columns have values; we don't want to flag those rows as errors if the user downloads the
    // template, edits some of the rows, and leaves the other example rows in place.
    val columnsWithValues = values.count { it != null }
    return columnsWithValues != 0 &&
        (values[5] == null || values[14] == null || columnsWithValues != 2)
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
