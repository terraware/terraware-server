package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.seedbank.ViabilityTestSubstrate
import com.terraformation.backend.db.seedbank.ViabilityTestType
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
                      ViabilityTestResultModel(recordingDate = LocalDate.EPOCH, seedsGerminated = 1)
                  ),
              testType = ViabilityTestType.Cut,
          )

      assertThrows<IllegalArgumentException> { model.validate() }
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
                    ViabilityTestModel(seedsFilled = 1, testType = testType),
                )
                .forEach { model ->
                  assertThrows<IllegalArgumentException>("$model") { model.validate() }
                }
          }
    }

    @Test
    fun `rejects invalid substrate for lab test`() {
      val model =
          ViabilityTestModel(
              substrate = ViabilityTestSubstrate.Moss,
              testType = ViabilityTestType.Lab,
          )

      assertThrows<IllegalArgumentException> { model.validate() }
    }

    @Test
    fun `rejects invalid substrate for nursery test`() {
      val model =
          ViabilityTestModel(
              substrate = ViabilityTestSubstrate.Agar,
              testType = ViabilityTestType.Nursery,
          )

      assertThrows<IllegalArgumentException> { model.validate() }
    }

    @Test
    fun `rejects cut tests with more results than seeds tested`() {
      val model =
          ViabilityTestModel(
              seedsCompromised = 1,
              seedsEmpty = 1,
              seedsFilled = 1,
              seedsTested = 2,
              testType = ViabilityTestType.Cut,
          )

      assertThrows<IllegalArgumentException> { model.validate() }
    }

    @Test
    fun `rejects negative seed counts`() {
      listOf(
              ViabilityTestModel(
                  seedsCompromised = -1,
                  seedsTested = 1,
                  testType = ViabilityTestType.Cut,
              ),
              ViabilityTestModel(
                  seedsEmpty = -1,
                  seedsTested = 1,
                  testType = ViabilityTestType.Cut,
              ),
              ViabilityTestModel(
                  seedsFilled = -1,
                  seedsTested = 1,
                  testType = ViabilityTestType.Cut,
              ),
              ViabilityTestModel(seedsTested = -1, testType = ViabilityTestType.Lab),
          )
          .forEach { model ->
            assertThrows<IllegalArgumentException>("$model") { model.validate() }
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
                          recordingDate = LocalDate.EPOCH,
                          seedsGerminated = 12,
                      ),
                      ViabilityTestResultModel(
                          recordingDate = LocalDate.EPOCH,
                          seedsGerminated = 27,
                      ),
                  ),
              testType = testType,
          )

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
              testType = ViabilityTestType.Cut,
          )

      assertEquals(50, model.calculateViabilityPercent())
    }
  }
}
