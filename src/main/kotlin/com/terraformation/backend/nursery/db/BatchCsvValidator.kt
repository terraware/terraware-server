package com.terraformation.backend.nursery.db

import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.i18n.toBigDecimal
import com.terraformation.backend.importer.CsvValidator
import java.time.LocalDate
import java.time.format.DateTimeParseException

class BatchCsvValidator(
    uploadId: UploadId,
    messages: Messages,
    private val subLocationNames: Set<String>,
    /** Today's date in the nursery's time zone. */
    private val today: LocalDate,
) : CsvValidator(uploadId, messages) {
  override val validators: List<((String?, String) -> Unit)?> =
      listOf(
          this::validateScientificName,
          null,
          this::validateQuantity,
          this::validateQuantity,
          this::validateQuantity,
          this::validateDateAdded,
          this::validateSubLocation,
      )

  override fun getColumnName(position: Int): String {
    return messages.batchCsvColumnName(position)
  }

  private fun validateDateAdded(value: String?, field: String) {
    if (value == null) {
      addError(
          UploadProblemType.MissingRequiredValue,
          field,
          null,
          messages.csvRequiredFieldMissing(),
      )
    } else {
      try {
        val date = LocalDate.parse(value)
        if (date > today) {
          addError(UploadProblemType.MalformedValue, field, value, messages.csvDateAddedInFuture())
        }
      } catch (_: DateTimeParseException) {
        addError(UploadProblemType.MalformedValue, field, value, messages.csvDateMalformed())
      }
    }
  }

  private fun validateQuantity(value: String?, field: String) {
    if (value != null) {
      val bigDecimalValue =
          try {
            value.toBigDecimal(currentLocale())
          } catch (e: Exception) {
            null
          }
      if (
          bigDecimalValue == null || bigDecimalValue.signum() == -1 || bigDecimalValue.scale() > 0
      ) {
        addError(
            UploadProblemType.MalformedValue,
            field,
            value,
            messages.batchCsvQuantityInvalid(),
            rowNum,
        )
      }
    }
  }

  private fun validateSubLocation(value: String?, field: String) {
    if (value != null && value.lines().map { it.trim() }.any { it !in subLocationNames }) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.csvSubLocationNotFound(),
          rowNum,
      )
    }
  }
}
