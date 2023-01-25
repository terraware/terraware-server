package com.terraformation.backend.species.db

import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.importer.CsvValidator

class SpeciesCsvValidator(
    uploadId: UploadId,
    private val existingScientificNames: Set<String>,
    /** Map of initial scientific names to current names for species that have been renamed. */
    private val existingRenames: Map<String, String>,
    messages: Messages,
) : CsvValidator(uploadId, messages) {
  private val validBooleans = messages.csvBooleanValues(true) + messages.csvBooleanValues(false)
  private val validGrowthForms =
      GrowthForm.values().map { it.getDisplayName(currentLocale()) }.toSet()
  private val validSeedStorageBehaviors =
      SeedStorageBehavior.values().map { it.getDisplayName(currentLocale()) }.toSet()

  override val validators: List<((String?, String) -> Unit)?> =
      listOf(
          this::validateUniqueScientificName,
          null,
          this::validateFamily,
          this::validateEndangered,
          this::validateRare,
          this::validateGrowthForm,
          this::validateSeedStorageBehavior,
      )

  override fun getColumnName(position: Int): String {
    return messages.speciesCsvColumnName(position)
  }

  override fun validateHeaderRow(rawValues: Array<String?>?): Boolean {
    return super.validateHeaderRow(rawValues) && headersExactlyMatchExpectedNames(rawValues)
  }

  private fun headersExactlyMatchExpectedNames(rawValues: Array<String?>?): Boolean {
    val columnNames = validators.indices.map { getColumnName(it) }
    return if (rawValues?.toList() != columnNames) {
      addError(UploadProblemType.MalformedValue, null, null, messages.csvBadHeader())
      false
    } else {
      true
    }
  }

  private fun validateUniqueScientificName(value: String?, field: String) {
    validateScientificName(value, field)

    if (value != null) {
      if (value in existingScientificNames) {
        addWarning(
            UploadProblemType.DuplicateValue,
            field,
            value,
            messages.speciesCsvScientificNameExists())
      } else if (value in existingRenames) {
        addWarning(
            UploadProblemType.DuplicateValue,
            field,
            "${existingRenames[value]} ($value)",
            messages.speciesCsvScientificNameExists())
      }
    }
  }

  private fun validateFamily(value: String?, field: String) {
    if (!value.isNullOrBlank()) {
      if (value.split(Regex("""\s""")).size > 1) {
        addError(
            UploadProblemType.MalformedValue,
            field,
            value,
            messages.speciesCsvFamilyMultipleWords())
      } else {
        val invalidChar = Regex("[^A-Za-z]").find(value)?.value
        if (invalidChar != null) {
          addError(
              UploadProblemType.MalformedValue,
              field,
              value,
              messages.speciesCsvFamilyInvalidChar(invalidChar),
          )
        }
      }
    }
  }

  private fun validateEndangered(value: String?, field: String) {
    if (!value.isNullOrBlank() && value.trim() !in validBooleans) {
      addError(
          UploadProblemType.UnrecognizedValue, field, value, messages.speciesCsvEndangeredInvalid())
    }
  }

  private fun validateRare(value: String?, field: String) {
    if (!value.isNullOrBlank() && value.trim() !in validBooleans) {
      addError(UploadProblemType.UnrecognizedValue, field, value, messages.speciesCsvRareInvalid())
    }
  }

  private fun validateGrowthForm(value: String?, field: String) {
    if (!value.isNullOrBlank() && value !in validGrowthForms) {
      addError(
          UploadProblemType.UnrecognizedValue, field, value, messages.speciesCsvGrowthFormInvalid())
    }
  }

  private fun validateSeedStorageBehavior(value: String?, field: String) {
    if (!value.isNullOrBlank() && value !in validSeedStorageBehaviors) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.speciesCsvSeedStorageBehaviorInvalid())
    }
  }
}
