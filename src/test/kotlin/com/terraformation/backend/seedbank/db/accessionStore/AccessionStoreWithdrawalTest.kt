package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AccessionStoreWithdrawalTest : AccessionStoreTest() {
  @Test
  fun `update forces state to Used Up if no seeds remaining`() {
    val initial = store.create(accessionModel(state = AccessionState.Processing))

    val withQuantity = store.updateAndFetch(initial.copy(remaining = seeds(1)))
    val updated =
        store.updateAndFetch(
            withQuantity.copy(
                state = AccessionState.Drying,
                withdrawals = listOf(WithdrawalModel(date = LocalDate.EPOCH, withdrawn = seeds(1))),
            )
        )

    assertEquals(AccessionState.UsedUp, updated.state)
  }

  @Test
  fun `update computes remaining quantity for count-based accessions`() {
    val accession =
        create()
            .andUpdate { it.copy(remaining = seeds(100)) }
            .andAdvanceClock(Duration.ofDays(1))
            .andUpdate {
              it.addWithdrawal(
                  WithdrawalModel(
                      date = LocalDate.EPOCH,
                      purpose = WithdrawalPurpose.Other,
                      withdrawn = seeds(10),
                  )
              )
            }

    assertEquals(
        seeds<SeedQuantityModel>(90),
        accession.remaining,
        "Quantity remaining on accession",
    )

    val quantityFromHistory =
        accessionQuantityHistoryDao
            .fetchByHistoryTypeId(AccessionQuantityHistoryType.Computed)
            .getOrNull(0)
            ?.remainingQuantity
    assertEquals(
        BigDecimal(90),
        quantityFromHistory,
        "Should have inserted quantity history row for new value",
    )
  }

  @Test
  fun `update computes withdrawn totals`() {
    val accession =
        create()
            .andUpdate {
              it.copy(remaining = grams(10), subsetCount = 1, subsetWeightQuantity = grams(2))
            }
            .andUpdate {
              it.addWithdrawal(
                  WithdrawalModel(
                      date = LocalDate.EPOCH,
                      purpose = WithdrawalPurpose.Other,
                      withdrawn = grams(4),
                  )
              )
            }

    assertEquals(2, accession.totalWithdrawnCount, "Total withdrawn count")
    assertEquals(grams(4), accession.totalWithdrawnWeight, "Total withdrawn weight")
  }
}
