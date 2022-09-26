package com.terraformation.backend.species.db

import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.UploadProblemType.DuplicateValue
import com.terraformation.backend.db.default_schema.UploadProblemType.MalformedValue
import com.terraformation.backend.db.default_schema.UploadProblemType.MissingRequiredValue
import com.terraformation.backend.db.default_schema.UploadProblemType.UnrecognizedValue
import com.terraformation.backend.db.default_schema.tables.pojos.UploadProblemsRow
import com.terraformation.backend.i18n.Messages
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class SpeciesCsvValidatorTest {
  private val messages = Messages()
  private val existingRenames = mapOf("Initial d" to "Renamed d")
  private val existingScientificNames = setOf("Existing a", "Existing b", "Existing c", "Renamed d")
  private val uploadId = UploadId(1)

  private val header =
      "Scientific Name,Common Name,Family,Endangered,Rare,Growth Form,Seed Storage Behavior"

  @Nested
  inner class HeaderRow {
    @Test
    fun `must include all expected columns`() {
      assertError(
          "Scientific Name,Family,Endangered,Rare,Growth Form,Seed Storage Behavior\n",
          MalformedValue,
          messages.csvBadHeader())
    }

    @Test
    fun `may not include unknown columns`() {
      assertError("Bogus,$header", MalformedValue, messages.csvBadHeader())
    }

    @Test
    fun `column names may be quoted`() {
      assertValidationResults(
          """"Scientific Name","Common Name","Family","Endangered","Rare","Growth Form","Seed Storage Behavior"""" +
              "\n")
    }

    private fun assertError(csv: String, type: UploadProblemType, message: String) {
      assertValidationResults(
          csv,
          errors =
              setOf(
                  UploadProblemsRow(
                      isError = true,
                      message = message,
                      position = 1,
                      typeId = type,
                      uploadId = uploadId)))
    }
  }

  @Nested
  inner class ScientificName {
    @Test
    fun `must not be empty`() {
      assertError("    ", MissingRequiredValue, messages.csvScientificNameMissing(), null)
    }

    @Test
    fun `must be at least two words`() {
      assertError("Word", MalformedValue, messages.csvScientificNameTooShort())
    }

    @Test
    fun `must be no more than four words`() {
      assertError("Word word word word word", MalformedValue, messages.csvScientificNameTooLong())
    }

    @Test
    fun `may not contain numbers`() {
      assertError("Name 1", MalformedValue, messages.csvScientificNameInvalidChar("1"))
    }

    @Test
    fun `may not contain punctuation other than periods`() {
      assertError("Name name var: x", MalformedValue, messages.csvScientificNameInvalidChar(":"))
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "Name name",
                "Name name var. name",
                "Name name subsp. name",
                "Name name f. name",
            ])
    fun `valid name formats are accepted`(scientificName: String) {
      assertValidationResults(csvWithScientificName(scientificName))
    }

    @Test
    fun `existing names are flagged as warnings`() {
      val csv = "$header\nExisting a,,,,,,\nNew name,,,,,,\nExisting b,,,,,,\n"

      assertValidationResults(
          csv,
          warnings =
              setOf(
                  UploadProblemsRow(
                      field = "Scientific Name",
                      isError = false,
                      message = messages.speciesCsvScientificNameExists(),
                      position = 2,
                      typeId = DuplicateValue,
                      uploadId = uploadId,
                      value = "Existing a"),
                  UploadProblemsRow(
                      field = "Scientific Name",
                      isError = false,
                      message = messages.speciesCsvScientificNameExists(),
                      position = 4,
                      typeId = DuplicateValue,
                      uploadId = uploadId,
                      value = "Existing b"),
              ))
    }

    @Test
    fun `duplicate initial names are flagged as duplicates of the current names`() {
      val csv = "$header\nInitial d,,,,,,\n"

      assertValidationResults(
          csv,
          warnings =
              setOf(
                  UploadProblemsRow(
                      field = "Scientific Name",
                      isError = false,
                      message = messages.speciesCsvScientificNameExists(),
                      position = 2,
                      typeId = DuplicateValue,
                      uploadId = uploadId,
                      value = "Renamed d (Initial d)")))
    }

    private fun csvWithScientificName(scientificName: String): String =
        "$header\n$scientificName,,,,,,"

    private fun assertError(
        scientificName: String,
        type: UploadProblemType,
        message: String,
        value: String? = scientificName
    ) {
      assertValidationResults(
          csvWithScientificName(scientificName),
          errors =
              setOf(
                  UploadProblemsRow(
                      field = "Scientific Name",
                      isError = true,
                      message = message,
                      position = 2,
                      typeId = type,
                      uploadId = uploadId,
                      value = value)))
    }
  }

  @Nested
  inner class FamilyName {
    @Test
    fun `may not have multiple words`() {
      assertError("Word word", MalformedValue, messages.speciesCsvFamilyMultipleWords())
    }

    @Test
    fun `may not contain numbers`() {
      assertError("Name1", MalformedValue, messages.speciesCsvFamilyInvalidChar("1"))
    }

    @Test
    fun `may not contain punctuation`() {
      assertError("Name.x", MalformedValue, messages.speciesCsvFamilyInvalidChar("."))
    }

    @Test
    fun `accepts valid names`() {
      assertValidationResults(csvWithFamilyName("Family"))
    }

    private fun csvWithFamilyName(familyName: String): String =
        "$header\nScientific Name,,$familyName,,,,"

    private fun assertError(
        familyName: String,
        type: UploadProblemType,
        message: String,
        value: String? = familyName
    ) {
      assertValidationResults(
          csvWithFamilyName(familyName),
          errors =
              setOf(
                  UploadProblemsRow(
                      field = "Family",
                      isError = true,
                      message = message,
                      position = 2,
                      typeId = type,
                      uploadId = uploadId,
                      value = value)))
    }
  }

  @Test
  fun `rejects rows with incorrect number of fields`() {
    val csv = "$header\n,,,\n,,,,,,,,,\n"

    assertValidationResults(
        csv,
        errors =
            setOf(
                UploadProblemsRow(
                    isError = true,
                    message = messages.csvWrongFieldCount(7, 4),
                    position = 2,
                    typeId = MalformedValue,
                    uploadId = uploadId,
                ),
                UploadProblemsRow(
                    isError = true,
                    message = messages.csvWrongFieldCount(7, 10),
                    position = 3,
                    typeId = MalformedValue,
                    uploadId = uploadId,
                )))
  }

  @Test
  fun `rejects unrecognized values for enumerated fields`() {
    val csv = "$header\nSci name,Common,Family,maybe,unknown,Mushroom,Impossible\n"

    assertValidationResults(
        csv,
        errors =
            setOf(
                UploadProblemsRow(
                    field = "Endangered",
                    isError = true,
                    message = messages.speciesCsvEndangeredInvalid(),
                    position = 2,
                    typeId = UnrecognizedValue,
                    uploadId = uploadId,
                    value = "maybe",
                ),
                UploadProblemsRow(
                    field = "Rare",
                    isError = true,
                    message = messages.speciesCsvRareInvalid(),
                    position = 2,
                    typeId = UnrecognizedValue,
                    uploadId = uploadId,
                    value = "unknown",
                ),
                UploadProblemsRow(
                    field = "Growth Form",
                    isError = true,
                    message = messages.speciesCsvGrowthFormInvalid(),
                    position = 2,
                    typeId = UnrecognizedValue,
                    uploadId = uploadId,
                    value = "Mushroom",
                ),
                UploadProblemsRow(
                    field = "Seed Storage Behavior",
                    isError = true,
                    message = messages.speciesCsvSeedStorageBehaviorInvalid(),
                    position = 2,
                    typeId = UnrecognizedValue,
                    uploadId = uploadId,
                    value = "Impossible",
                ),
            ))
  }

  @Test
  fun `accepts both UNIX-style and Windows-style line separators`() {
    val csv = "$header\nScientific a,,,,,,\r\nScientific b,,,,,,\r\n"
    assertValidationResults(csv)
  }

  private fun assertValidationResults(
      csv: String,
      errors: Set<UploadProblemsRow> = emptySet(),
      warnings: Set<UploadProblemsRow> = emptySet()
  ) {
    val validator =
        SpeciesCsvValidator(uploadId, existingScientificNames, existingRenames, messages)
    validator.validate(csv.byteInputStream())

    val expected = mapOf("errors" to errors, "warnings" to warnings)
    val actual =
        mapOf("errors" to validator.errors.toSet(), "warnings" to validator.warnings.toSet())

    assertEquals(expected, actual)
  }
}
