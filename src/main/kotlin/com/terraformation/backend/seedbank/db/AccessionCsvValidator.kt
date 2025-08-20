package com.terraformation.backend.seedbank.db

import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.importer.CsvValidator
import com.terraformation.backend.seedbank.model.isV2Compatible
import java.text.ParseException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class AccessionCsvValidator(
    uploadId: UploadId,
    messages: Messages,
    private val getCountryCode: (String) -> String?,
    private val findExistingAccessionNumbers: (Collection<String>) -> Collection<String>,
) : CsvValidator(uploadId, messages) {
  companion object {
    private val validStates = ConcurrentHashMap<Locale, Set<String>>()
    private val validUnits = ConcurrentHashMap<Locale, Set<String>>()

    private fun isValidState(state: String): Boolean {
      val locale = currentLocale()
      val statesForLocale =
          validStates.getOrPut(locale) {
            AccessionState.entries
                .filter { it.isV2Compatible }
                .map { it.getDisplayName(locale) }
                .toSet()
          }
      return state in statesForLocale
    }

    private fun isValidUnit(unit: String): Boolean {
      val locale = currentLocale()
      val unitsForLocale =
          validUnits.getOrPut(locale) {
            SeedQuantityUnits.entries.map { it.getDisplayName(locale) }.toSet()
          }
      return unit in unitsForLocale
    }
  }

  override val validators: List<((String?, String) -> Unit)?> =
      listOf(
          this::validateAccessionNumber,
          this::validateScientificName,
          null,
          this::validateQuantity,
          this::validateUnits,
          this::validateStatus,
          this::validateDate,
          null,
          null,
          null,
          null,
          this::validateCountryCode,
          null,
          null,
          this::validateCollectionSource,
          this::validateNumberOfPlants,
          null,
          this::validateLatitude,
          this::validateLongitude,
      )

  override val rowValidators = listOf(this::validateLatitudeLongitude, this::validateUsedUpQuantity)

  override fun getColumnName(position: Int): String {
    return messages.accessionCsvColumnName(position)
  }

  private val accessionNumberToRow = mutableMapOf<String, Int>()

  override fun checkFilePostConditions() {
    if (accessionNumberToRow.isNotEmpty()) {
      val existingNumbers = findExistingAccessionNumbers(accessionNumberToRow.keys)
      existingNumbers.forEach { existingNumber ->
        val existingRowNum = accessionNumberToRow[existingNumber]
        if (existingRowNum != null) {
          addWarning(
              UploadProblemType.DuplicateValue,
              messages.accessionCsvColumnName(0),
              existingNumber,
              messages.accessionCsvNumberExists(),
              existingRowNum,
          )
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
            messages.accessionCsvNumberDuplicate(existingRow),
        )
      } else {
        accessionNumberToRow[value] = rowNum
      }
    }
  }

  private fun validateQuantity(value: String?, field: String) {
    if (value != null) {
      val floatValue =
          try {
            decimalFormat.parse(value).toFloat()
          } catch (e: NumberFormatException) {
            null
          }
      if (floatValue == null || floatValue < 0) {
        addError(
            UploadProblemType.MalformedValue,
            field,
            value,
            messages.accessionCsvQuantityInvalid(),
        )
      }
    }
  }

  private fun validateUnits(value: String?, field: String) {
    if (value != null && !isValidUnit(value)) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.accessionCsvQuantityUnitsInvalid(),
      )
    }
  }

  private fun validateCountryCode(value: String?, field: String) {
    if (value != null && getCountryCode(value) == null) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.accessionCsvCountryInvalid(),
      )
    }
  }

  private fun validateStatus(value: String?, field: String) {
    if (value != null && !isValidState(value)) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.accessionCsvStatusInvalid(),
      )
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
            messages.accessionCsvNumberOfPlantsInvalid(),
        )
      }
    }
  }

  private fun validateCollectionSource(value: String?, field: String) {
    if (value != null && value.toCollectionSource(currentLocale()) == null) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.accessionCsvCollectionSourceInvalid(),
      )
    }
  }

  private fun validateLatitude(value: String?, field: String) {
    if (value != null) {
      val floatValue =
          try {
            decimalFormat.parse(value).toFloat()
          } catch (e: NumberFormatException) {
            null
          } catch (e: ParseException) {
            null
          }
      if (floatValue == null || floatValue < -90.0 || floatValue > 90.0) {
        addError(
            UploadProblemType.MalformedValue,
            field,
            value,
            messages.accessionCsvLatitudeInvalid(),
        )
      }
    }
  }

  private fun validateLongitude(value: String?, field: String) {
    if (value != null) {
      val floatValue =
          try {
            decimalFormat.parse(value).toFloat()
          } catch (e: NumberFormatException) {
            null
          } catch (e: ParseException) {
            null
          }
      if (floatValue == null || floatValue < -180.0 || floatValue > 180.0) {
        addError(
            UploadProblemType.MalformedValue,
            field,
            value,
            messages.accessionCsvLongitudeInvalid(),
        )
      }
    }
  }

  private fun validateLatitudeLongitude(values: List<String?>) {
    if (values[17] == null && values[18] != null) {
      addError(
          UploadProblemType.MissingRequiredValue,
          getColumnName(17),
          null,
          messages.accessionCsvLatitudeLongitude(),
      )
    } else if (values[17] != null && values[18] == null) {
      addError(
          UploadProblemType.MissingRequiredValue,
          getColumnName(18),
          null,
          messages.accessionCsvLatitudeLongitude(),
      )
    }
  }

  private fun validateUsedUpQuantity(values: List<String?>) {
    val quantity = values[3]?.toDoubleOrNull()

    if (values[5] == AccessionState.UsedUp.getDisplayName(currentLocale()) && quantity != 0.0) {
      addError(
          UploadProblemType.MalformedValue,
          getColumnName(3),
          values[3],
          messages.accessionCsvNonZeroUsedUpQuantity(),
      )
    }
  }
}

/**
 * In the template, the collection source names have additional explanatory suffixes such as "(In
 * Situ)". We want to accept but not require those suffixes.
 */
fun String.toCollectionSource(locale: Locale): CollectionSource? {
  return CollectionSource.entries.firstOrNull {
    startsWith(it.getDisplayName(locale), ignoreCase = true)
  }
}
