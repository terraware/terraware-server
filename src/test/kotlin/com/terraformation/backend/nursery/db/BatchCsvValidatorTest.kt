package com.terraformation.backend.nursery.db

import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.tables.pojos.UploadProblemsRow
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.toGibberish
import com.terraformation.backend.i18n.use
import java.io.InputStreamReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class BatchCsvValidatorTest {
  private val header: String by lazy {
    javaClass.getResourceAsStream("/csv/batches-template.csv").use { inputStream ->
      InputStreamReader(inputStream!!).readText()
    }
  }

  private val messages = Messages()
  private val uploadId = UploadId(1)

  @Test
  fun `accepts template`() {
    assertValidationResults(header)
  }

  @Test
  fun `accepts well-formed row`() {
    assertValidationResults(
        header +
            "\nScientific name,Common name,1,2,2022-01-23,\"Valid Location\n  Valid Location 2 \""
    )
  }

  @Test
  fun `rejects empty file`() {
    assertValidationResults(
        "",
        errors =
            setOf(
                UploadProblemsRow(
                    isError = true,
                    message = messages.csvBadHeader(),
                    position = 1,
                    typeId = UploadProblemType.MissingRequiredValue,
                    uploadId = uploadId,
                ),
            ),
    )
  }

  @Test
  fun `rejects header row with too few columns`() {
    assertValidationResults(
        "1,2,3,4",
        errors =
            setOf(
                UploadProblemsRow(
                    isError = true,
                    message = messages.csvBadHeader(),
                    position = 1,
                    typeId = UploadProblemType.MalformedValue,
                    uploadId = uploadId,
                ),
            ),
    )
  }

  @Test
  fun `rejects header row with too many columns`() {
    assertValidationResults(
        "1,2,3,4,5,6,7",
        errors =
            setOf(
                UploadProblemsRow(
                    isError = true,
                    message = messages.csvBadHeader(),
                    position = 1,
                    typeId = UploadProblemType.MalformedValue,
                    uploadId = uploadId,
                ),
            ),
    )
  }

  @Test
  fun `rejects row with missing or invalid date`() {
    assertValidationResults(
        "$header\nScientific name,,0,1,,\nScientific name,,0,1,Jan 18,",
        errors =
            setOf(
                UploadProblemsRow(
                    field = "Stored Date",
                    isError = true,
                    message = messages.csvRequiredFieldMissing(),
                    position = 2,
                    typeId = UploadProblemType.MissingRequiredValue,
                    uploadId = uploadId,
                ),
                UploadProblemsRow(
                    field = "Stored Date",
                    isError = true,
                    message = messages.csvDateMalformed(),
                    position = 3,
                    typeId = UploadProblemType.MalformedValue,
                    uploadId = uploadId,
                    value = "Jan 18",
                ),
            ),
    )
  }

  @Test
  fun `rejects rows with invalid scientific names`() {
    assertValidationResults(
        "$header\n" +
            "This name is way too long,,0,1,2022-01-01,\n" +
            "Short,,0,1,2022-01-01,\n" +
            "Bad character!,,0,1,2022-01-01,",
        setOf(
            UploadProblemsRow(
                field = "Species (Scientific Name)",
                isError = true,
                message = messages.csvScientificNameTooLong(),
                position = 2,
                typeId = UploadProblemType.MalformedValue,
                uploadId = uploadId,
                value = "This name is way too long",
            ),
            UploadProblemsRow(
                field = "Species (Scientific Name)",
                isError = true,
                message = messages.csvScientificNameTooShort(),
                position = 3,
                typeId = UploadProblemType.MalformedValue,
                uploadId = uploadId,
                value = "Short",
            ),
            UploadProblemsRow(
                field = "Species (Scientific Name)",
                isError = true,
                message = messages.csvScientificNameInvalidChar("!"),
                position = 4,
                typeId = UploadProblemType.MalformedValue,
                uploadId = uploadId,
                value = "Bad character!",
            ),
        ),
    )
  }

  @Test
  fun `rejects rows with invalid seedling counts`() {
    assertValidationResults(
        "$header\n" +
            "Scientific name,,A,1,2022-01-01,\n" +
            "Scientific name,,-1,1,2022-01-01,\n" +
            "Scientific name,,1.5,1,2022-01-01,\n" +
            "Scientific name,,1,A,2022-01-01,\n" +
            "Scientific name,,1,-1,2022-01-01,\n" +
            "Scientific name,,1,1.5,2022-01-01,\n",
        setOf(
            UploadProblemsRow(
                field = "Germinating Quantity",
                isError = true,
                message = messages.batchCsvQuantityInvalid(),
                position = 2,
                typeId = UploadProblemType.MalformedValue,
                uploadId = uploadId,
                value = "A",
            ),
            UploadProblemsRow(
                field = "Germinating Quantity",
                isError = true,
                message = messages.batchCsvQuantityInvalid(),
                position = 3,
                typeId = UploadProblemType.MalformedValue,
                uploadId = uploadId,
                value = "-1",
            ),
            UploadProblemsRow(
                field = "Germinating Quantity",
                isError = true,
                message = messages.batchCsvQuantityInvalid(),
                position = 4,
                typeId = UploadProblemType.MalformedValue,
                uploadId = uploadId,
                value = "1.5",
            ),
            UploadProblemsRow(
                field = "Seedling Quantity",
                isError = true,
                message = messages.batchCsvQuantityInvalid(),
                position = 5,
                typeId = UploadProblemType.MalformedValue,
                uploadId = uploadId,
                value = "A",
            ),
            UploadProblemsRow(
                field = "Seedling Quantity",
                isError = true,
                message = messages.batchCsvQuantityInvalid(),
                position = 6,
                typeId = UploadProblemType.MalformedValue,
                uploadId = uploadId,
                value = "-1",
            ),
            UploadProblemsRow(
                field = "Seedling Quantity",
                isError = true,
                message = messages.batchCsvQuantityInvalid(),
                position = 7,
                typeId = UploadProblemType.MalformedValue,
                uploadId = uploadId,
                value = "1.5",
            ),
        ),
    )
  }

  @Test
  fun `rejects row with nonexistent sub-location`() {
    assertValidationResults(
        "$header\nScientific name,,0,1,2021-02-03,Bogus Location",
        errors =
            setOf(
                UploadProblemsRow(
                    field = "Sub-Location",
                    isError = true,
                    message = messages.csvSubLocationNotFound(),
                    position = 2,
                    typeId = UploadProblemType.UnrecognizedValue,
                    uploadId = uploadId,
                    value = "Bogus Location",
                ),
            ),
    )
  }

  @Test
  fun `returns localized field names in problem data`() {
    Locales.GIBBERISH.use {
      assertValidationResults(
          "$header\nThis name is way too long,,0,1,2022-01-01,\n",
          setOf(
              UploadProblemsRow(
                  field = "Species (Scientific Name)".toGibberish(),
                  isError = true,
                  message = messages.csvScientificNameTooLong(),
                  position = 2,
                  typeId = UploadProblemType.MalformedValue,
                  uploadId = uploadId,
                  value = "This name is way too long",
              )
          ),
      )
    }
  }

  @Test
  fun `accepts localized number formatting`() {
    Locales.GIBBERISH.use {
      assertValidationResults("$header\nScientific name,,0,123&456,2022-01-01,\n")
    }
  }

  private fun assertValidationResults(
      csv: String,
      errors: Set<UploadProblemsRow> = emptySet(),
      warnings: Set<UploadProblemsRow> = emptySet(),
  ) {
    val validator =
        BatchCsvValidator(uploadId, messages, setOf("Valid Location", "Valid Location 2"))
    validator.validate(csv.byteInputStream())

    val expected = mapOf("errors" to errors, "warnings" to warnings)
    val actual =
        mapOf("errors" to validator.errors.toSet(), "warnings" to validator.warnings.toSet())

    assertEquals(expected, actual)
  }
}
