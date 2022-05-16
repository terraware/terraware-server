package com.terraformation.backend.species.db

import com.opencsv.CSVReader
import com.terraformation.backend.db.GrowthForm
import com.terraformation.backend.db.SeedStorageBehavior
import com.terraformation.backend.db.UploadId
import com.terraformation.backend.db.UploadProblemType
import com.terraformation.backend.db.tables.pojos.UploadProblemsRow
import com.terraformation.backend.i18n.Messages
import java.io.InputStream
import java.io.InputStreamReader
import org.apache.commons.lang3.BooleanUtils

class SpeciesCsvValidator(
    private val uploadId: UploadId,
    private val existingScientificNames: Set<String>,
    private val messages: Messages,
) {
  private var rowNum = 1
  val warnings = mutableListOf<UploadProblemsRow>()
  val errors = mutableListOf<UploadProblemsRow>()

  companion object {
    private val validGrowthForms = GrowthForm.values().map { it.displayName }.toSet()
    private val validSeedStorageBehaviors =
        SeedStorageBehavior.values().map { it.displayName }.toSet()
  }

  fun validate(inputStream: InputStream) {
    val csvReader = CSVReader(InputStreamReader(inputStream))

    validateHeaderRow(csvReader.readNext())

    csvReader.forEach { values ->
      rowNum++
      validateDataRow(values)
    }
  }

  private fun validateHeaderRow(values: Array<String?>?) {
    if (values?.asList() != SPECIES_CSV_HEADERS) {
      addError(UploadProblemType.MalformedValue, null, null, messages.speciesCsvBadHeader())
    }
  }

  private fun validateDataRow(rawValues: Array<String?>) {
    if (rawValues.size != SPECIES_CSV_HEADERS.size) {
      addError(
          UploadProblemType.MalformedValue,
          null,
          null,
          messages.speciesCsvWrongFieldCount(SPECIES_CSV_HEADERS.size, rawValues.size))
      // Field count is wrong, so we don't know which value is which; no point validating the
      // individual fields.
      return
    }

    // Ignore leading and trailing whitespace, and represent empty values as null.
    val values = rawValues.map { it?.trim()?.ifEmpty { null } }

    validateScientificName(values[0], SPECIES_CSV_HEADERS[0])
    // No validation for common name
    validateFamily(values[2], SPECIES_CSV_HEADERS[2])
    validateEndangered(values[3], SPECIES_CSV_HEADERS[3])
    validateRare(values[4], SPECIES_CSV_HEADERS[4])
    validateGrowthForm(values[5], SPECIES_CSV_HEADERS[5])
    validateSeedStorageBehavior(values[6], SPECIES_CSV_HEADERS[6])
  }

  private fun validateScientificName(value: String?, field: String) {
    if (value.isNullOrBlank()) {
      addError(
          UploadProblemType.MissingRequiredValue,
          field,
          null,
          messages.speciesCsvScientificNameMissing(),
      )
    } else {
      val invalidChar = Regex("[^A-Za-z. ]").find(value)?.value
      if (invalidChar != null) {
        addError(
            UploadProblemType.MalformedValue,
            field,
            value,
            messages.speciesCsvScientificNameInvalidChar(invalidChar),
        )
      }

      val wordCount = value.split(' ').size
      if (wordCount < 2) {
        addError(
            UploadProblemType.MalformedValue,
            field,
            value,
            messages.speciesCsvScientificNameTooShort())
      } else if (wordCount > 4) {
        addError(
            UploadProblemType.MalformedValue,
            field,
            value,
            messages.speciesCsvScientificNameTooLong())
      }

      if (value in existingScientificNames) {
        addWarning(
            UploadProblemType.DuplicateValue,
            field,
            value,
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
    if (!value.isNullOrBlank() && BooleanUtils.toBooleanObject(value.trim()) == null) {
      addError(
          UploadProblemType.UnrecognizedValue, field, value, messages.speciesCsvEndangeredInvalid())
    }
  }

  private fun validateRare(value: String?, field: String) {
    if (!value.isNullOrBlank() && BooleanUtils.toBooleanObject(value.trim()) == null) {
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
          messages.speciesCsvSeedStorageBehaviorInvalid(),
      )
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

  private fun addWarning(type: UploadProblemType, field: String?, value: String?, message: String) {
    warnings +=
        UploadProblemsRow(
            isError = false,
            field = field,
            message = message,
            position = rowNum,
            typeId = type,
            uploadId = uploadId,
            value = value,
        )
  }
}
