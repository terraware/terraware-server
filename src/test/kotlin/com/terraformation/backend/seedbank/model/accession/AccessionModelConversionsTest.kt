package com.terraformation.backend.seedbank.model.accession

import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.seedbank.AccessionId
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

  @Test
  fun `V1 to V2 weight-based accession with scheduled withdrawals`() {
    val initial =
        AccessionModel(
            checkedInTime = yesterdayInstant,
            id = AccessionId(175),
            processingMethod = ProcessingMethod.Weight,
            remaining = grams(5),
            state = AccessionState.InStorage,
            subsetCount = 10,
            subsetWeightQuantity = grams(2),
            total = grams(50),
            withdrawals =
                listOf(
                    WithdrawalModel(
                        date = today,
                        remaining = grams(10),
                        withdrawn = seeds(12),
                        id = WithdrawalId(95),
                        accessionId = AccessionId(175),
                        purpose = WithdrawalPurpose.ViabilityTesting,
                        createdTime = yesterdayInstant,
                        viabilityTestId = ViabilityTestId(37),
                    ),
                    WithdrawalModel(
                        date = tomorrow,
                        remaining = grams(5),
                        withdrawn = grams(5),
                        id = WithdrawalId(96),
                        accessionId = AccessionId(175),
                        purpose = WithdrawalPurpose.Other,
                        createdTime = yesterdayInstant,
                    ),
                ),
            viabilityTests =
                listOf(
                    ViabilityTestModel(
                        startDate = today,
                        remaining = grams(10),
                        seedsTested = 12,
                        id = ViabilityTestId(37),
                        accessionId = AccessionId(175),
                        testType = ViabilityTestType.Nursery,
                    )))

    initial.toV2Compatible(yesterdayClock)
  }
}
