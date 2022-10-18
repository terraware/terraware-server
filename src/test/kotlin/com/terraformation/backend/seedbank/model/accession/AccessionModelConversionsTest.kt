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
import org.junit.jupiter.api.Test

internal class AccessionModelConversionsTest : AccessionModelTest() {
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
}
