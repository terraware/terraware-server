package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.api.WithdrawalPayload
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionStoreWithdrawalTest : AccessionStoreTest() {
  @Test
  fun `update recalculates seeds remaining on withdrawal`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    store.update(initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(10)))
    val fetched = store.fetchOneById(initial.id!!)

    Assertions.assertEquals(seeds(10), fetched.remaining)
  }

  @Test
  fun `update forces state to Used Up if no seeds remaining`() {
    val initial =
        store.create(
            AccessionModel(
                facilityId = facilityId,
                isManualState = true,
                state = AccessionState.Processing,
            ))

    val withQuantity = store.updateAndFetch(initial.copy(remaining = seeds(1)))
    val updated =
        store.updateAndFetch(
            withQuantity.copy(
                state = AccessionState.Drying,
                withdrawals =
                    listOf(
                        WithdrawalModel(
                            date = LocalDate.EPOCH, remaining = seeds(0), withdrawn = seeds(1)))))

    Assertions.assertEquals(AccessionState.UsedUp, updated.state)
  }

  @Test
  fun `update rejects weight-based withdrawals for count-based accessions`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<IllegalArgumentException> {
      store.update(
          initial.copy(
              processingMethod = ProcessingMethod.Count,
              total = seeds(50),
              withdrawals =
                  listOf(
                      WithdrawalModel(
                          date = LocalDate.now(clock),
                          purpose = WithdrawalPurpose.Other,
                          withdrawn = grams(1)))))
    }
  }

  @Test
  fun `update rejects withdrawals without remaining quantity for weight-based accessions`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<IllegalArgumentException> {
      store.update(
          initial.copy(
              processingMethod = ProcessingMethod.Weight,
              total = grams(100),
              withdrawals =
                  listOf(
                      WithdrawalModel(
                          date = LocalDate.now(clock), purpose = WithdrawalPurpose.Other))))
    }
  }

  @Test
  fun `update computes remaining quantity on withdrawals for count-based accessions`() {
    val initial = createAndUpdate {
      it.copy(processingMethod = ProcessingMethod.Count, initialQuantity = seeds(100))
    }

    val withWithdrawal =
        store.updateAndFetch(
            initial.copy(
                withdrawals =
                    listOf(
                        WithdrawalModel(
                            date = LocalDate.EPOCH,
                            purpose = WithdrawalPurpose.Other,
                            withdrawn = seeds(10)))))

    Assertions.assertEquals(
        seeds<SeedQuantityModel>(90),
        withWithdrawal.withdrawals[0].remaining,
        "Quantity remaining on withdrawal")
    Assertions.assertEquals(
        seeds<SeedQuantityModel>(90), withWithdrawal.remaining, "Quantity remaining on accession")

    val quantityFromHistory =
        accessionQuantityHistoryDao
            .fetchByHistoryTypeId(AccessionQuantityHistoryType.Computed)
            .getOrNull(0)
            ?.remainingQuantity
    Assertions.assertEquals(
        BigDecimal(90),
        quantityFromHistory,
        "Should have inserted quantity history row for new value")
  }

  @Test
  fun `update rejects withdrawals if accession total size not set`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<IllegalArgumentException> {
      store.update(
          initial.copy(
              processingMethod = ProcessingMethod.Count,
              withdrawals =
                  listOf(
                      WithdrawalModel(
                          date = LocalDate.EPOCH,
                          purpose = WithdrawalPurpose.Other,
                          withdrawn = seeds(1)))))
    }
  }

  @Test
  fun `update allows processing method to change if no tests or withdrawals exist`() {
    val initial = createAndUpdate { it.copy(processingMethod = ProcessingMethod.Weight) }

    val withCountMethod =
        store.updateAndFetch(
            initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(1)))
    Assertions.assertEquals(seeds<SeedQuantityModel>(1), withCountMethod.total)

    val withWeightMethod =
        store.updateAndFetch(
            withCountMethod.copy(processingMethod = ProcessingMethod.Weight, total = grams(2)))
    Assertions.assertEquals(grams<SeedQuantityModel>(2), withWeightMethod.total)
  }

  @Test
  fun `update does not allow processing method to change if withdrawal exists`() {
    val initial = createAndUpdate {
      it.copy(
          processingMethod = ProcessingMethod.Weight,
          initialQuantity = grams(10),
          withdrawals =
              listOf(
                  WithdrawalPayload(
                      date = LocalDate.EPOCH,
                      purpose = WithdrawalPurpose.Other,
                      remainingQuantity = grams(5))))
    }

    assertThrows<IllegalArgumentException> {
      store.update(initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(10)))
    }
  }
}
