package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AccessionStoreWithdrawalTest : AccessionStoreTest() {
  @Test
  fun `update recalculates seeds remaining on withdrawal`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    store.update(initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(10)))
    val fetched = store.fetchOneById(initial.id!!)

    assertEquals(seeds(10), fetched.remaining)
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

    assertEquals(AccessionState.UsedUp, updated.state)
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

    assertEquals(
        seeds<SeedQuantityModel>(90),
        withWithdrawal.withdrawals[0].remaining,
        "Quantity remaining on withdrawal")
    assertEquals(
        seeds<SeedQuantityModel>(90), withWithdrawal.remaining, "Quantity remaining on accession")

    val quantityFromHistory =
        accessionQuantityHistoryDao
            .fetchByHistoryTypeId(AccessionQuantityHistoryType.Computed)
            .getOrNull(0)
            ?.remainingQuantity
    assertEquals(
        BigDecimal(90),
        quantityFromHistory,
        "Should have inserted quantity history row for new value")
  }
}
