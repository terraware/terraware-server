package com.terraformation.backend.species.db

import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.PlantMaterialSourcingMethod
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SuccessionalGroup
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.importer.CsvValidator
import java.util.Locale

class SpeciesCsvValidator(
    uploadId: UploadId,
    private val existingScientificNames: Set<String>,
    /** Map of initial scientific names to current names for species that have been renamed. */
    private val existingRenames: Map<String, String>,
    messages: Messages,
) : CsvValidator(uploadId, messages) {
  companion object {
    val MULTIPLE_VALUE_DELIMITER = Regex("\\s*[\\r\\n]+\\s*")
  }

  private val validBooleans = messages.csvBooleanValues(true) + messages.csvBooleanValues(false)
  private val validEcosystemTypes =
      EcosystemType.entries.map { it.getDisplayName(currentLocale()) }.toSet()
  private val validGrowthForms =
      GrowthForm.entries.map { it.getDisplayName(currentLocale()) }.toSet()
  private val validPlantMaterialSourcingMethods =
      PlantMaterialSourcingMethod.entries.map { it.getDisplayName(currentLocale()) }.toSet()
  private val validSeedStorageBehaviors =
      SeedStorageBehavior.entries.map { it.getDisplayName(currentLocale()) }.toSet()
  private val validSuccessionalGroups =
      SuccessionalGroup.entries.map { it.getDisplayName(currentLocale()) }.toSet()

  override val validators: List<((String?, String) -> Unit)?> =
      listOf(
          this::validateUniqueScientificName,
          null,
          this::validateFamily,
          this::validateConservationCategory,
          this::validateRare,
          this::validateGrowthForm,
          this::validateSeedStorageBehavior,
          this::validateEcosystemTypes,
          null,
          this::validateSuccessionalGroup,
          null,
          null,
          this::validatePlantMaterialSourcingMethod,
          null,
      )

  override fun getColumnName(position: Int): String {
    return messages.speciesCsvColumnName(position)
  }

  private fun validateUniqueScientificName(value: String?, field: String) {
    validateScientificName(value, field)

    if (value != null) {
      if (value in existingScientificNames) {
        addWarning(
            UploadProblemType.DuplicateValue,
            field,
            value,
            messages.speciesCsvScientificNameExists(),
        )
      } else if (value in existingRenames) {
        addWarning(
            UploadProblemType.DuplicateValue,
            field,
            "${existingRenames[value]} ($value)",
            messages.speciesCsvScientificNameExists(),
        )
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
            messages.speciesCsvFamilyMultipleWords(),
        )
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

  private fun validateConservationCategory(value: String?, field: String) {
    if (
        !value.isNullOrBlank() &&
            ConservationCategory.forId(value.trim().uppercase(Locale.ENGLISH)) == null
    ) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.speciesCsvConservationCategoryInvalid(),
      )
    }
  }

  private fun validateRare(value: String?, field: String) {
    if (!value.isNullOrBlank() && value.trim() !in validBooleans) {
      addError(UploadProblemType.UnrecognizedValue, field, value, messages.speciesCsvRareInvalid())
    }
  }

  private fun validateGrowthForm(value: String?, field: String) {
    if (
        !value.isNullOrBlank() &&
            value.split(MULTIPLE_VALUE_DELIMITER).any { it !in validGrowthForms }
    ) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.speciesCsvGrowthFormInvalid(),
      )
    }
  }

  private fun validateSeedStorageBehavior(value: String?, field: String) {
    if (!value.isNullOrBlank() && value !in validSeedStorageBehaviors) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.speciesCsvSeedStorageBehaviorInvalid(),
      )
    }
  }

  private fun validateEcosystemTypes(value: String?, field: String) {
    if (
        !value.isNullOrBlank() &&
            value.split(MULTIPLE_VALUE_DELIMITER).any { it !in validEcosystemTypes }
    ) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.speciesCsvEcosystemTypesInvalid(),
      )
    }
  }

  private fun validateSuccessionalGroup(value: String?, field: String) {
    if (
        !value.isNullOrBlank() &&
            value.split(MULTIPLE_VALUE_DELIMITER).any { it !in validSuccessionalGroups }
    ) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.speciesCsvSuccessionalGroupInvalid(),
      )
    }
  }

  private fun validatePlantMaterialSourcingMethod(value: String?, field: String) {
    if (
        !value.isNullOrBlank() &&
            value.split(MULTIPLE_VALUE_DELIMITER).any { it !in validPlantMaterialSourcingMethods }
    ) {
      addError(
          UploadProblemType.UnrecognizedValue,
          field,
          value,
          messages.speciesCsvPlantMaterialSourcingMethodInvalid(),
      )
    }
  }
}
