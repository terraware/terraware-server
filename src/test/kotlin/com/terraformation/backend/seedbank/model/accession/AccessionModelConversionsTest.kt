package com.terraformation.backend.seedbank.model.accession

import com.terraformation.backend.seedbank.seeds
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class AccessionModelConversionsTest {

  @Test
  fun `viability percent is overwritten with computed value`() {
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
  fun `backdated viability tests with more seeds than initial quantity`() {
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
  fun `backdated withdrawals with more seeds than initial quantity`() {
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
