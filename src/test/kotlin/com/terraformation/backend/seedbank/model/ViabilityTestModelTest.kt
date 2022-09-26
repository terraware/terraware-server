package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.ViabilityTestSubstrate
import com.terraformation.backend.db.ViabilityTestType
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

internal class ViabilityTestModelTest {
  @Nested
  inner class V2Validation {
    @Test
    fun `rejects cut tests with germination test results`() {
      val model =
          ViabilityTestModel(
              testResults =
                  listOf(
                      ViabilityTestResultModel(
                          recordingDate = LocalDate.EPOCH, seedsGerminated = 1)),
              testType = ViabilityTestType.Cut)

      assertThrows<IllegalArgumentException> { model.validateV2() }
    }

    @Test
    fun `rejects lab and nursery tests with cut test results`() {
      listOf(
              ViabilityTestType.Lab,
              ViabilityTestType.Nursery,
          )
          .forEach { testType ->
            listOf(
                    ViabilityTestModel(seedsCompromised = 1, testType = testType),
                    ViabilityTestModel(seedsEmpty = 1, testType = testType),
                    ViabilityTestModel(seedsFilled = 1, testType = testType))
                .forEach { model ->
                  assertThrows<IllegalArgumentException>("$model") { model.validateV2() }
                }
          }
    }

    @Test
    fun `rejects invalid substrate for lab test`() {
      val model =
          ViabilityTestModel(
              substrate = ViabilityTestSubstrate.Moss, testType = ViabilityTestType.Lab)

      assertThrows<IllegalArgumentException> { model.validateV2() }
    }

    @Test
    fun `rejects invalid substrate for nursery test`() {
      val model =
          ViabilityTestModel(
              substrate = ViabilityTestSubstrate.Agar, testType = ViabilityTestType.Nursery)

      assertThrows<IllegalArgumentException> { model.validateV2() }
    }

    @Test
    fun `rejects cut tests with more results than seeds tested`() {
      val model =
          ViabilityTestModel(
              seedsCompromised = 1,
              seedsEmpty = 1,
              seedsFilled = 1,
              seedsTested = 2,
              testType = ViabilityTestType.Cut)

      assertThrows<IllegalArgumentException> { model.validateV2() }
    }

    @Test
    fun `rejects negative seed counts`() {
      listOf(
              ViabilityTestModel(
                  seedsCompromised = -1, seedsTested = 1, testType = ViabilityTestType.Cut),
              ViabilityTestModel(
                  seedsEmpty = -1, seedsTested = 1, testType = ViabilityTestType.Cut),
              ViabilityTestModel(
                  seedsFilled = -1, seedsTested = 1, testType = ViabilityTestType.Cut),
              ViabilityTestModel(seedsTested = -1, testType = ViabilityTestType.Lab),
          )
          .forEach { model ->
            assertThrows<IllegalArgumentException>("$model") { model.validateV2() }
          }
    }
  }

  @Nested
  inner class ViabilityPercentCalculations {
    @EnumSource
    @ParameterizedTest
    fun `no viability percent calculated when there are no results`(testType: ViabilityTestType) {
      val model = ViabilityTestModel(seedsTested = 10, testType = testType)

      assertNull(model.calculateViabilityPercent())
    }

    @EnumSource(names = ["Lab", "Nursery"])
    @ParameterizedTest
    fun `viability percent is based on total of all results`(testType: ViabilityTestType) {
      val model =
          ViabilityTestModel(
              seedsTested = 100,
              testResults =
                  listOf(
                      ViabilityTestResultModel(
                          recordingDate = LocalDate.EPOCH, seedsGerminated = 12),
                      ViabilityTestResultModel(
                          recordingDate = LocalDate.EPOCH, seedsGerminated = 27),
                  ),
              testType = testType)

      assertEquals(39, model.calculateViabilityPercent())
    }

    @Test
    fun `viability percent of cut tests is based on seeds filled`() {
      val model =
          ViabilityTestModel(
              seedsCompromised = 1,
              seedsEmpty = 2,
              seedsFilled = 3,
              seedsTested = 6,
              testType = ViabilityTestType.Cut)

      assertEquals(50, model.calculateViabilityPercent())
    }
  }

  @Nested
  inner class V1ToV2Conversion {
    @Test
    fun `staff responsible is added to notes`() {
      fun model(notes: String?, staffResponsible: String?) =
          ViabilityTestModel(
              notes = notes,
              staffResponsible = staffResponsible,
              seedsTested = 1,
              testType = ViabilityTestType.Lab)

      val testCases: List<Pair<ViabilityTestModel, String?>> =
          listOf(
              model(null, null) to null,
              model(" ", "  ") to " ",
              model("existing notes", null) to "existing notes",
              model("existing notes", " ") to "existing notes",
              model("existing notes with staff name", "staff name") to
                  "existing notes with staff name\n\nStaff responsible: staff name",
              model("existing\nStaff responsible: staff name", "staff name") to
                  "existing\nStaff responsible: staff name",
              model(null, "staff name") to "Staff responsible: staff name",
              model("existing notes", "staff name") to
                  "existing notes\n\nStaff responsible: staff name",
          )

      val actual = testCases.map { it.first to it.first.toV2Compatible().notes }

      assertEquals(testCases, actual)
    }

    @Test
    fun `substrate is removed if it is not valid for v2 nursery test`() {
      val v1Model =
          ViabilityTestModel(
              seedsTested = 1,
              substrate = ViabilityTestSubstrate.Agar,
              testType = ViabilityTestType.Nursery,
          )

      val v2Model = v1Model.toV2Compatible()
      assertNull(v2Model.substrate)
    }

    @Test
    fun `substrate is retained if it is valid for v2 nursery test`() {
      val v1Model =
          ViabilityTestModel(
              seedsTested = 1,
              substrate = ViabilityTestSubstrate.Other,
              testType = ViabilityTestType.Nursery,
          )

      val v2Model = v1Model.toV2Compatible()
      assertEquals(ViabilityTestSubstrate.Other, v2Model.substrate)
    }
  }

  @Nested
  inner class V2ToV1Conversion {
    @Test
    fun `substrate is removed if it did not exist in v1`() {
      val v2Model =
          ViabilityTestModel(
              seedsTested = 1,
              substrate = ViabilityTestSubstrate.Moss,
              testType = ViabilityTestType.Nursery,
          )

      val v1Model = v2Model.toV1Compatible()
      assertNull(v1Model.substrate)
    }

    @Test
    fun `substrate is preserved if it existed in v1`() {
      val v2Model =
          ViabilityTestModel(
              seedsTested = 1,
              substrate = ViabilityTestSubstrate.Agar,
              testType = ViabilityTestType.Lab,
          )

      val v1Model = v2Model.toV1Compatible()
      assertEquals(ViabilityTestSubstrate.Agar, v1Model.substrate)
    }
  }
}
