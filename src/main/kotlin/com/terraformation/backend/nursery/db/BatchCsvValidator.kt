package com.terraformation.backend.nursery.db

import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.i18n.toBigDecimal
import com.terraformation.backend.importer.CsvValidator

class BatchCsvValidator(
    uploadId: UploadId,
    messages: Messages,
    private val subLocationNames: Set<String>,
) : CsvValidator(uploadId, messages) {
  override val validators: List<((String?, String) -> Unit)?> =
      listOf(
          this::validateScientificName,
          null,
          this::validateQuantity,
          this::validateQuantity,
          this::validateDate,
          this::validateSubLocation,
      )

  override fun getColumnName(position: Int): String {
    return messages.batchCsvColumnName(position)
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
