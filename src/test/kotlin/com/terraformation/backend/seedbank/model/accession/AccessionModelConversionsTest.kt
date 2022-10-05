package com.terraformation.backend.seedbank.model.accession

import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class AccessionModelConversionsTest : AccessionModelTests() {
  @Test
  fun `V1 to V2 weight-based accession with no subset data and a viability test`() {
    val initial =
        AccessionModel(
            checkedInTime = yesterdayInstant,
            createdTime = yesterdayInstant,
            processingMethod = ProcessingMethod.Weight,
            total = grams(10),
            viabilityTests =
                listOf(
                    ViabilityTestModel(
                        id = ViabilityTestId(1),
                        remaining = grams(9),
                        seedsTested = 1,
                        startDate = today,
                        testType = ViabilityTestType.Lab,
                    ),
                ),
            withdrawals =
                listOf(
                    WithdrawalModel(
                        createdTime = todayInstant,
                        date = today,
                        id = WithdrawalId(1),
                        purpose = WithdrawalPurpose.ViabilityTesting,
                        remaining = grams(9),
                        viabilityTestId = ViabilityTestId(1),
                        withdrawn = seeds(1),
                    ),
                ),
        )

    val expected =
        AccessionModel(
            checkedInTime = yesterdayInstant,
            createdTime = yesterdayInstant,
            estimatedWeight = grams(9),
            isManualState = true,
            latestObservedQuantity = grams(9),
            latestObservedTime = todayInstant,
            processingMethod = ProcessingMethod.Weight,
            remaining = grams(9),
            state = AccessionState.AwaitingProcessing,
            total = grams(10),
            viabilityTests =
                listOf(
                    ViabilityTestModel(
                        id = ViabilityTestId(1),
                        remaining = grams(9),
                        seedsTested = 1,
                        startDate = today,
                        testType = ViabilityTestType.Lab,
                    ),
                ),
            withdrawals =
                listOf(
                    WithdrawalModel(
                        createdTime = todayInstant,
                        date = today,
                        estimatedCount = 1,
                        estimatedWeight = grams(1),
                        id = WithdrawalId(1),
                        purpose = WithdrawalPurpose.ViabilityTesting,
                        remaining = grams(9),
                        viabilityTest =
                            ViabilityTestModel(
                                id = ViabilityTestId(1),
                                remaining = grams(9),
                                seedsTested = 1,
                                startDate = today,
                                testType = ViabilityTestType.Lab,
                            ),
                        viabilityTestId = ViabilityTestId(1),
                        weightDifference = grams(1),
                        withdrawn = seeds(1),
                    ),
                ),
        )

    assertJsonEquals(expected, initial.toV2Compatible(tomorrowClock))
  }

  @Test
  fun `V2 to V1 viability percent is overwritten with computed value`() {
    val expectedPercent = 40
    val v2Model =
        accession()
            .copy(isManualState = true, remaining = seeds(500))
            .withCalculatedValues(clock)
            .addViabilityTest(
                viabilityTest(
                    seedsTested = 100,
                    testResults =
                        listOf(
                            viabilityTestResult(
                                recordingDate = january(2), seedsGerminated = expectedPercent))),
                clock)
    Assertions.assertNull(v2Model.totalViabilityPercent)

    val v1Model = v2Model.toV1Compatible(clock)
    Assertions.assertEquals(40, v1Model.totalViabilityPercent)
  }

  @Test
  fun `V2 to V1 backdated viability tests with more seeds than initial quantity`() {
    val initialV2Model =
        accession()
            .copy(isManualState = true, remaining = seeds(2))
            .withCalculatedValues(yesterdayClock)
    val v2Model =
        initialV2Model
            .copy(latestObservedQuantityCalculated = false, remaining = seeds(1))
            .withCalculatedValues(clock, initialV2Model)
            // In v2, this is valid because it is dated yesterday and we have an observed quantity
            // from today that overrides what would otherwise be a negative seeds remaining value.
            .addViabilityTest(viabilityTest(seedsTested = 3, startDate = yesterday), clock)

    val v1Model = v2Model.toV1Compatible(clock)
    Assertions.assertEquals(seeds(1), v1Model.viabilityTests.getOrNull(0)?.remaining)
  }

  @Test
  fun `V2 to V1 backdated withdrawals with more seeds than initial quantity`() {
    val initialV2Model =
        accession()
            .copy(isManualState = true, remaining = seeds(2))
            .withCalculatedValues(yesterdayClock)
    val v2Model =
        initialV2Model
            .copy(latestObservedQuantityCalculated = false, remaining = seeds(1))
            .withCalculatedValues(clock, initialV2Model)
            // In v2, this is valid because it is yesterday and we have an observed quantity from
            // today that overrides what would otherwise be a negative seeds remaining value.
            .addWithdrawal(withdrawal(seeds(3), date = yesterday, id = null), clock)

    val v1Model = v2Model.toV1Compatible(clock)
    Assertions.assertEquals(seeds(1), v1Model.withdrawals.getOrNull(0)?.remaining)
  }
}
