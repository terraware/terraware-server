package com.terraformation.backend.seedbank.model.accession

import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.milligrams
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.seeds
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

internal class AccessionModelCalculationsTest : AccessionModelTest() {
  @Nested
  inner class WeightBasedAccessionCalculations {
    @Test
    fun `observed quantity is remaining quantity if no withdrawals`() {
      val accession = accession(remaining = grams(50))
      assertAll(
          { assertEquals(grams(50), accession.calculateLatestObservedQuantity(), "Quantity") },
          { assertEquals(clock.instant(), accession.calculateLatestObservedTime(clock), "Time") })
    }

    @Test
    fun `estimated seed count is null if information is missing`() {
      listOf(1, null).forEach { subsetCount ->
        listOf(BigDecimal("1.1"), null).forEach { subsetWeight ->
          listOf(BigDecimal("5.5"), null).forEach { totalWeight ->
            val values = listOf(subsetCount, subsetWeight, totalWeight)
            if (values.any { it == null }) {
              val accession =
                  accession(
                      subsetCount = subsetCount,
                      subsetWeight = SeedQuantityModel.of(subsetWeight, SeedQuantityUnits.Grams),
                      remaining = SeedQuantityModel.of(totalWeight, SeedQuantityUnits.Grams))
              assertNull(
                  accession.calculateEstimatedSeedCount(accession.remaining),
                  "Estimated seed count: $values")
            }
          }
        }
      }
    }

    @Test
    fun `estimated seed count is calculated based on weight`() {
      val accession =
          accession(remaining = grams(1), subsetCount = 10, subsetWeight = milligrams(200))
              .withCalculatedValues(clock)

      assertEquals(50, accession.estimatedSeedCount)
    }

    @Test
    fun `calculateRemaining returns value in same units as accession total weight`() {
      val accession =
          accession(remaining = milligrams(2000))
              .withCalculatedValues(clock)
              .copy(withdrawals = listOf(withdrawal(withdrawn = grams(1))))

      assertEquals(milligrams(1000), accession.calculateRemaining(clock))
    }

    @Test
    fun `calculateWithdrawals rejects IDs not in caller-supplied withdrawal list`() {
      val accession = accession(remaining = grams(100), withdrawals = listOf(withdrawal(grams(1))))

      assertThrows<IllegalArgumentException> {
        accession.calculateWithdrawals(
            clock, AccessionModel(withdrawals = listOf(withdrawal(grams(1)))))
      }
    }

    @Test
    fun `calculateWithdrawals looks in caller-supplied withdrawal list for test withdrawals`() {
      val viabilityTest = viabilityTest(seedsTested = 1)
      val existingWithdrawalForTest = withdrawal(grams(5), viabilityTestId = viabilityTest.id)
      val otherExistingWithdrawal = withdrawal(grams(1))

      val accession =
          accession(
              remaining = grams(100),
              viabilityTests = listOf(viabilityTest),
              withdrawals =
                  listOf(
                      otherExistingWithdrawal.copy(
                          viabilityTestId = viabilityTest.id,
                          purpose = WithdrawalPurpose.ViabilityTesting)))

      val withdrawals =
          accession.calculateWithdrawals(
              clock,
              accession(
                  remaining = grams(100),
                  withdrawals = listOf(existingWithdrawalForTest, otherExistingWithdrawal)))
      assertEquals(existingWithdrawalForTest.id, withdrawals[0].id, "Withdrawal ID")
    }
  }

  @Nested
  inner class CountBasedAccessionCalculations {
    @Test
    fun `observed quantity is null if accession has no processing method`() {
      val accession = accession()
      assertAll(
          { assertNull(accession.calculateLatestObservedQuantity(), "Quantity") },
          { assertNull(accession.calculateLatestObservedTime(clock), "Time") },
      )
    }

    @Test
    fun `observed quantity is null if accession has no initial quantity`() {
      val accession = accession()
      assertAll(
          { assertNull(accession.calculateLatestObservedQuantity(), "Quantity") },
          { assertNull(accession.calculateLatestObservedTime(clock), "Time") },
      )
    }

    @Test
    fun `observed quantity is initial quantity if present`() {
      val accession = accession(remaining = seeds(10))
      assertAll(
          { assertEquals(seeds(10), accession.calculateLatestObservedQuantity(), "Quantity") },
          { assertEquals(clock.instant(), accession.calculateLatestObservedTime(clock), "Time") },
      )
    }

    @Test
    fun `withdrawn seeds are subtracted from seeds remaining`() {
      val accession =
          accession(remaining = seeds(100))
              .withCalculatedValues(clock)
              .copy(
                  withdrawals =
                      listOf(
                          withdrawal(seeds(1)),
                          withdrawal(seeds(5)),
                      ))

      assertEquals(seeds(94), accession.calculateRemaining(clock))
    }

    @Test
    fun `withdrawals for viability testing without corresponding tests are not subtracted from seeds remaining`() {
      val accession =
          accession(remaining = seeds(100))
              .withCalculatedValues(clock)
              .copy(
                  withdrawals =
                      listOf(
                          withdrawal(seeds(1), purpose = WithdrawalPurpose.ViabilityTesting),
                          withdrawal(seeds(5)),
                      ))

      assertEquals(seeds(95), accession.calculateRemaining(clock))
    }

    @Test
    fun `calculateWithdrawals walks tests and withdrawals in time order`() {
      val accession =
          accession(
              remaining = seeds(100),
              viabilityTests =
                  listOf(
                      viabilityTest(seedsTested = 4, startDate = january(2)),
                      viabilityTest(seedsTested = 8, startDate = january(4)),
                  ),
              withdrawals =
                  listOf(
                      withdrawal(seeds(2), date = january(3)),
                      withdrawal(seeds(1), date = january(1)),
                  ),
          )

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(
          listOf<SeedQuantityModel>(seeds(1), seeds(4), seeds(2), seeds(8)),
          withdrawals.map { it.withdrawn })
    }

    @Test
    fun `calculateWithdrawals retains withdrawal date if test is undated`() {
      val test = viabilityTest(seedsTested = 1, startDate = null)
      val accession =
          accession(
              remaining = seeds(100),
              viabilityTests = listOf(test),
              withdrawals =
                  listOf(withdrawal(seeds(1), date = january(15), viabilityTestId = test.id)),
          )

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(january(15), withdrawals[0].date)
    }

    @Test
    fun `calculateWithdrawals updates withdrawal date based on updated test date`() {
      val test = viabilityTest(seedsTested = 1, startDate = january(5))
      val accession =
          accession(
              remaining = seeds(100),
              viabilityTests = listOf(test),
              withdrawals =
                  listOf(withdrawal(seeds(1), date = january(15), viabilityTestId = test.id)),
          )

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(
          january(5), withdrawals[0].date, "Withdrawal date should be copied from test date")
      assertEquals(
          accession.withdrawals[0].id, withdrawals[0].id, "Should update existing test withdrawal")
    }
  }

  @Nested
  inner class StateRelatedCalculations {
    @TestFactory
    fun stateTransitionRules(): List<DynamicTest> {
      val tests = mutableListOf<DynamicTest>()

      fun AccessionModel.addStateTest(
          modify: AccessionModel.() -> AccessionModel,
          expectedState: AccessionState?,
          displayName: String,
      ): AccessionModel {
        val newModel = modify()
        tests.add(
            DynamicTest.dynamicTest("$state -> $expectedState: $displayName") {
              val transition = getStateTransition(newModel, clock)
              if (expectedState == null || expectedState == state) {
                assertNull(transition, "Unexpected state transition $transition")
              } else {
                assertEquals(
                    expectedState, transition?.newState, "Unexpected state transition $transition")
              }
            })

        return newModel
      }

      // Make sure that manual state updates are applied except in specific cases, and that
      // the correct automatic state updates happen even when the accession allows state editing.
      accession(
              state = AccessionState.AwaitingProcessing,
          )
          .addStateTest(
              { copy(state = AccessionState.UsedUp) },
              AccessionState.AwaitingProcessing,
              "Can't change to Used Up when no quantity has been set")

      accession(
              latestObservedQuantity = seeds(10),
              latestObservedTime = Instant.EPOCH,
              remaining = seeds(10),
              state = AccessionState.InStorage,
              withdrawals = listOf(withdrawal(seeds(10))),
          )
          .withCalculatedValues(clock)
          .addStateTest({ this }, AccessionState.UsedUp, "All seeds marked as withdrawn")
          .addStateTest(
              { copy(state = AccessionState.InStorage) },
              AccessionState.UsedUp,
              "Can't change from Used Up when no seeds remaining")
          .copy(state = AccessionState.UsedUp)
          .addStateTest(
              { copy(withdrawals = emptyList()) },
              AccessionState.InStorage,
              "Accession is no longer used up")
          .addStateTest(
              { copy(state = AccessionState.Drying) },
              AccessionState.Drying,
              "Change from InStorage to Drying")
          .addStateTest(
              { copy(state = AccessionState.Processing) },
              AccessionState.Processing,
              "Change from Drying to Processing")
          .addStateTest(
              { copy(state = AccessionState.AwaitingProcessing) },
              AccessionState.AwaitingProcessing,
              "Change from Processing to Awaiting Processing")
          .addStateTest(
              { copy(state = AccessionState.AwaitingCheckIn) },
              AccessionState.AwaitingProcessing,
              "Can't change back to Awaiting Check-In")
          .addStateTest(
              { copy(state = AccessionState.InStorage) },
              AccessionState.InStorage,
              "Change from Awaiting Check-In to In Storage")

      return tests
    }

    @Test
    fun `can move out of Used Up if remaining quantity and state are set at the same time`() {
      val initial =
          accession(remaining = seeds(10))
              .withCalculatedValues(clock)
              .addWithdrawal(withdrawal(seeds(10), id = null), clock)

      assertEquals(
          AccessionState.UsedUp, initial.state, "Withdrawal of all seeds sets state to Used Up")

      val updated =
          initial
              .copy(
                  remaining = seeds(5),
                  state = AccessionState.Drying,
                  latestObservedQuantityCalculated = false)
              .withCalculatedValues(clock, initial)

      assertEquals(seeds(5), updated.remaining, "Manual update of remaining quantity is accepted")
      assertEquals(AccessionState.Drying, updated.state, "Manual update of state is accepted")
    }
  }

  @Nested
  inner class QuantityCalculations {
    @Test
    fun `observed quantity is updated if remaining quantity is changed`() {
      val existing = accession(remaining = seeds(10))

      val updated = existing.copy(remaining = seeds(9))

      assertEquals(seeds(9), updated.calculateLatestObservedQuantity(existing), "Observed quantity")
      assertEquals(
          tomorrowInstant,
          updated.calculateLatestObservedTime(tomorrowClock, existing),
          "Observed time")
    }

    @Test
    fun `observed quantity is not updated if remaining quantity is unchanged`() {
      val accession =
          accession(
              latestObservedQuantity = seeds(10),
              latestObservedTime = yesterdayInstant,
              remaining = seeds(9))

      val updated = accession.copy(remaining = seeds(9))

      assertEquals(
          seeds(10), updated.calculateLatestObservedQuantity(accession), "Observed quantity")
      assertEquals(
          yesterdayInstant,
          updated.calculateLatestObservedTime(tomorrowClock, accession),
          "Observed time")
    }

    @Test
    fun `observed quantity is not affected by new withdrawals`() {
      val accession =
          accession(
              latestObservedQuantity = seeds(10),
              latestObservedTime = yesterdayInstant,
              remaining = seeds(10))

      val updated = accession.copy(withdrawals = listOf(withdrawal(seeds(2), date = today)))

      assertEquals(
          seeds(10), updated.calculateLatestObservedQuantity(accession), "Observed quantity")
      assertEquals(
          yesterdayInstant,
          updated.calculateLatestObservedTime(tomorrowClock, accession),
          "Observed time")
    }

    // SW-1723
    @Test
    fun `observed quantity is not affected by repeated calls to withCalculatedValues with same base model`() {
      val accession = accession(remaining = seeds(10)).withCalculatedValues(yesterdayClock)
      assertEquals(seeds(10), accession.latestObservedQuantity, "Original observed quantity")

      val withWithdrawal =
          accession.addWithdrawal(withdrawal(seeds(2), date = today, id = null), clock)
      assertEquals(
          seeds(10), accession.latestObservedQuantity, "Observed quantity after adding withdrawal")

      val recalculated = withWithdrawal.withCalculatedValues(tomorrowClock, accession)
      assertEquals(
          seeds(10), recalculated.latestObservedQuantity, "Observed quantity after recalculating")
      assertEquals(
          yesterdayInstant, recalculated.latestObservedTime, "Observed time after recalculating")
    }

    @Test
    fun `remaining quantity is updated by withdrawal dated after observed quantity`() {
      val accession =
          accession(
              latestObservedQuantity = seeds(10),
              latestObservedTime = yesterdayInstant,
              remaining = seeds(10))

      val updated =
          accession.copy(withdrawals = listOf(withdrawal(seeds(2), date = today, id = null)))

      assertEquals(
          seeds(8), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    @Test
    fun `remaining quantity is not affected by withdrawals dated before latest observed quantity`() {
      val accession =
          accession(
              latestObservedQuantity = grams(10),
              latestObservedTime = todayInstant,
              remaining = grams(10))

      val updated =
          accession.copy(
              withdrawals =
                  listOf(
                      withdrawal(
                          grams(1), date = yesterday, createdTime = tomorrowInstant, id = null)))

      assertEquals(
          grams(10), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    @Test
    fun `remaining quantity is not affected by withdrawals created earlier on the day of the observed quantity`() {
      val accession =
          accession(
              latestObservedQuantity = grams(10),
              latestObservedTime = todayInstant,
              remaining = grams(10))

      val updated =
          accession.copy(
              withdrawals =
                  listOf(
                      withdrawal(
                          grams(1), date = today, createdTime = yesterdayInstant, id = null)))

      assertEquals(
          grams(10), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    @Test
    fun `remaining quantity is updated by withdrawals created later on the day of the observed quantity`() {
      val accession =
          accession(
                  latestObservedQuantity = grams(10),
                  latestObservedTime = todayInstant,
                  remaining = grams(10))
              .withCalculatedValues(clock)

      val updated =
          accession.copy(
              withdrawals =
                  listOf(
                      withdrawal(
                          grams(1),
                          date = today,
                          createdTime = todayInstant.plusSeconds(1),
                          id = null)))

      assertEquals(
          grams(9), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    @Test
    fun `remaining quantity in weight is updated by withdrawal in seeds`() {
      val accession =
          accession(
              latestObservedQuantity = grams(10),
              latestObservedTime = Instant.EPOCH,
              remaining = grams(10),
              // 3 grams per seed
              subsetCount = 2,
              subsetWeight = grams(6))

      val updated = accession.copy(withdrawals = listOf(withdrawal(seeds(2), id = null)))

      assertEquals(
          grams(4), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    @Test
    fun `remaining quantity is updated by viability test but observed quantity is not`() {
      val accession =
          accession(
              latestObservedQuantity = seeds(10),
              latestObservedTime = Instant.EPOCH,
              remaining = seeds(10))

      val updated = accession.copy(viabilityTests = listOf(viabilityTest(seedsTested = 2)))

      assertEquals(
          seeds(10), updated.calculateLatestObservedQuantity(accession), "Observed quantity")
      assertEquals(
          Instant.EPOCH,
          updated.calculateLatestObservedTime(tomorrowClock, accession),
          "Observed time")
      assertEquals(
          seeds(8), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    @Test
    fun `remaining quantity change takes precedence over newly-added withdrawal`() {
      val accession =
          accession(
              latestObservedQuantity = seeds(10),
              latestObservedTime = Instant.EPOCH,
              remaining = seeds(10))

      val updated =
          accession.copy(
              remaining = seeds(5), withdrawals = listOf(withdrawal(seeds(1), id = null)))

      assertEquals(
          seeds(5), updated.calculateLatestObservedQuantity(accession), "Observed quantity")
      assertEquals(
          tomorrowInstant,
          updated.calculateLatestObservedTime(tomorrowClock, accession),
          "Observed time")
      assertEquals(
          seeds(5), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    @Test
    fun `estimated seed count is the same as remaining quantity if it is count-based`() {
      val accession =
          accession(remaining = seeds(10))
              .withCalculatedValues(clock)
              .addWithdrawal(withdrawal(seeds(1), date = tomorrow, id = null), tomorrowClock)

      assertEquals(9, accession.estimatedSeedCount)
    }

    @Test
    fun `estimated seed count is null if remaining quantity is weight-based and no subset data`() {
      val accession = accession(remaining = grams(10)).withCalculatedValues(clock)

      assertNull(accession.estimatedSeedCount)
    }

    @Test
    fun `estimated seed count is calculated based on subset data`() {
      val accession =
          accession(remaining = grams(10), subsetCount = 1, subsetWeight = grams(2))
              .withCalculatedValues(clock)
              .addWithdrawal(withdrawal(grams(1), date = tomorrow, id = null), tomorrowClock)

      // 9 grams, 2 grams per seed = 4.5 seeds, rounded up to 5
      assertEquals(5, accession.estimatedSeedCount)
    }

    @Test
    fun `estimated weight is the same as remaining quantity if it is weight-based`() {
      val accession =
          accession(remaining = grams(10))
              .withCalculatedValues(clock)
              .addWithdrawal(withdrawal(grams(1), date = tomorrow, id = null), tomorrowClock)

      assertEquals(grams(9), accession.estimatedWeight)
    }

    @Test
    fun `estimated weight is null if remaining quantity is count-based and no subset data`() {
      val accession = accession(remaining = seeds(10)).withCalculatedValues(clock)

      assertNull(accession.estimatedWeight)
    }

    @Test
    fun `estimated weight is calculated based on subset data`() {
      val accession =
          accession(remaining = seeds(10), subsetCount = 2, subsetWeight = grams(1))
              .withCalculatedValues(clock)

      assertEquals(grams(5), accession.estimatedWeight)
    }
  }
}
