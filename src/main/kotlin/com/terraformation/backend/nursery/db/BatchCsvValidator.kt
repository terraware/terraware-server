package com.terraformation.backend.nursery.db

import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.importer.CsvValidator

class BatchCsvValidator(uploadId: UploadId, messages: Messages) : CsvValidator(uploadId, messages) {
  override val columns: List<Pair<String, ((String?, String) -> Unit)?>> =
      listOf(
          "Species (Scientific Name)" to this::validateScientificName,
          "Species (Common Name)" to null,
          "Germinating Quantity" to this::validateQuantity,
          "Seedling Quantity" to this::validateQuantity,
          "Stored Date" to this::validateDate,
      )

  private fun validateQuantity(value: String?, field: String) {
    if (value != null) {
      val intValue = value.toIntOrNull()
      if (intValue == null || intValue < 0) {
        addError(
            UploadProblemType.MalformedValue,
            field,
            value,
            messages.batchCsvQuantityInvalid(),
            rowNum)
      }
    }
  }
}
