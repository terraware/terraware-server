package com.terraformation.seedbank.model

import com.terraformation.seedbank.api.seedbank.AccessionPayload
import com.terraformation.seedbank.api.seedbank.GerminationPayload
import com.terraformation.seedbank.api.seedbank.GerminationTestPayload
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.GerminationTestType
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class AccessionModelTest {
  private fun accession(
      germinationTests: List<GerminationTestPayload>,
      cutTestSeedsCompromised: Int? = null,
      cutTestSeedsEmpty: Int? = null,
      cutTestSeedsFilled: Int? = null
  ): AccessionPayload {
    return AccessionPayload(
        accessionNumber = "dummy",
        state = AccessionState.Processing,
        active = AccessionActive.Active,
        cutTestSeedsEmpty = cutTestSeedsEmpty,
        cutTestSeedsFilled = cutTestSeedsFilled,
        cutTestSeedsCompromised = cutTestSeedsCompromised,
        germinationTests = germinationTests)
  }

  private fun january(day: Int): LocalDate {
    return LocalDate.of(2021, 1, day)
  }

  @Test
  fun `test without seeds sown is ignored`() {
    val model =
        accession(
            listOf(
                GerminationTestPayload(
                    testType = GerminationTestType.Lab,
                    startDate = january(1),
                    germinations =
                        listOf(
                            GerminationPayload(recordingDate = january(2), seedsGerminated = 1),
                        ),
                ),
            ),
        )

    assertNull(model.calculateLatestGerminationRecordingDate(), "Latest germination test date")
    assertNull(model.calculateLatestViabilityPercent(), "Latest viability percent")
    assertNull(model.calculateTotalViabilityPercent(), "Total viability percent")
  }

  @Test
  fun `test with most recent recording date is selected`() {
    val model =
        accession(
            listOf(
                GerminationTestPayload(
                    testType = GerminationTestType.Lab,
                    startDate = january(1),
                    seedsSown = 5,
                    germinations =
                        listOf(
                            GerminationPayload(recordingDate = january(13), seedsGerminated = 1),
                        ),
                ),
                GerminationTestPayload(
                    testType = GerminationTestType.Lab,
                    startDate = january(3),
                    seedsSown = 4,
                    germinations =
                        listOf(
                            GerminationPayload(recordingDate = january(14), seedsGerminated = 1),
                            GerminationPayload(recordingDate = january(11), seedsGerminated = 1),
                        ),
                ),
                GerminationTestPayload(
                    testType = GerminationTestType.Lab,
                    startDate = january(8), // most recent start date
                    seedsSown = 3,
                    germinations =
                        listOf(
                            GerminationPayload(recordingDate = january(12), seedsGerminated = 3),
                        ),
                ),
            ),
        )

    assertEquals(
        january(14),
        model.calculateLatestGerminationRecordingDate(),
        "Latest germination test date")
    assertEquals(50, model.calculateLatestViabilityPercent(), "Latest viability percent")
  }

  @Test
  fun `total viability percentage includes all germination tests and cut test`() {
    val model =
        accession(
            germinationTests =
                listOf(
                    GerminationTestPayload(
                        testType = GerminationTestType.Lab,
                        startDate = january(1),
                        seedsSown = 10,
                        germinations =
                            listOf(
                                GerminationPayload(recordingDate = january(2), seedsGerminated = 4),
                                GerminationPayload(recordingDate = january(3), seedsGerminated = 3),
                            ),
                    ),
                    GerminationTestPayload(
                        testType = GerminationTestType.Lab,
                        startDate = january(8),
                        seedsSown = 5,
                        germinations =
                            listOf(
                                GerminationPayload(recordingDate = january(9), seedsGerminated = 1),
                            ),
                    ),
                ),
            cutTestSeedsCompromised = 2,
            cutTestSeedsEmpty = 1,
            cutTestSeedsFilled = 5,
        )

    // 13 seeds viable of 23 tested
    val expectedPercentage = 56

    assertEquals(expectedPercentage, model.calculateTotalViabilityPercent())
  }

  @Test
  fun `total viability percentage not set if no test results`() {
    val model =
        accession(
            germinationTests =
                listOf(
                    GerminationTestPayload(
                        testType = GerminationTestType.Lab,
                        startDate = january(1),
                        seedsSown = 10,
                    ),
                ),
        )

    assertNull(model.calculateTotalViabilityPercent())
  }

  @Test
  fun `total viability percentage ignores cut test results if values are missing`() {
    val missingEmpty =
        accession(
            germinationTests =
                listOf(
                    GerminationTestPayload(
                        testType = GerminationTestType.Lab,
                        startDate = january(1),
                        seedsSown = 10,
                        germinations =
                            listOf(
                                GerminationPayload(recordingDate = january(2), seedsGerminated = 4),
                            ),
                    ),
                ),
            cutTestSeedsCompromised = 2,
            cutTestSeedsFilled = 5,
        )

    val missingCompromised =
        missingEmpty.copy(cutTestSeedsEmpty = 1, cutTestSeedsCompromised = null)
    val missingFilled = missingEmpty.copy(cutTestSeedsEmpty = 1, cutTestSeedsFilled = null)

    // 4 seeds germinated out of 10 sown; cut test is ignored
    val expectedPercentage = 40

    assertEquals(
        expectedPercentage, missingEmpty.calculateTotalViabilityPercent(), "Empty count missing")
    assertEquals(
        expectedPercentage,
        missingCompromised.calculateTotalViabilityPercent(),
        "Compromised count missing")
    assertEquals(
        expectedPercentage, missingFilled.calculateTotalViabilityPercent(), "Filled count missing")
  }

  @Test
  fun `most recent recording date is used as test date`() {
    val model =
        accession(
            listOf(
                GerminationTestPayload(
                    testType = GerminationTestType.Lab,
                    startDate = january(1),
                    seedsSown = 1,
                    germinations =
                        listOf(
                            GerminationPayload(recordingDate = january(3), seedsGerminated = 1),
                            GerminationPayload(recordingDate = january(4), seedsGerminated = 2),
                            GerminationPayload(recordingDate = january(2), seedsGerminated = 3),
                        ),
                ),
            ),
        )

    assertEquals(january(4), model.calculateLatestGerminationRecordingDate())
  }
}
