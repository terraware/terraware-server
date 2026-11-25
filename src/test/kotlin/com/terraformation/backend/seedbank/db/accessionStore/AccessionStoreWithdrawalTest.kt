package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.default_schema.WithdrawalCustomPurposeType
import com.terraformation.backend.db.default_schema.tables.references.WITHDRAWAL_CUSTOM_PURPOSES
import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEvent
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.kilograms
import com.terraformation.backend.seedbank.milligrams
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
              it.copy(
                  remaining = kilograms(1000),
                  subsetCount = 10,
                  subsetWeightQuantity = milligrams(1),
              )
            }
            .andUpdate {
              it.addWithdrawal(
                  WithdrawalModel(
                      date = LocalDate.EPOCH,
                      purpose = WithdrawalPurpose.Other,
                      withdrawn = kilograms(500),
                  )
              )
            }

    assertEquals(5_000_000_000L, accession.estimatedSeedCount, "Estimated seed count")
    assertEquals(5_000_000_000L, accession.totalWithdrawnCount, "Total withdrawn count")
    assertEquals(kilograms(500), accession.totalWithdrawnWeight, "Total withdrawn weight")
  }

  @Test
  fun `publishes estimated quantities in WithdrawalCreatedEvent`() {
    create()
        .andUpdate {
          it.copy(remaining = grams(100), subsetCount = 10, subsetWeightQuantity = grams(1))
        }
        .andUpdate {
          it.addWithdrawal(
                  WithdrawalModel(
                      date = LocalDate.EPOCH,
                      purpose = WithdrawalPurpose.Other,
                      withdrawn = grams(13),
                  )
              )
              .addWithdrawal(
                  WithdrawalModel(
                      date = LocalDate.EPOCH,
                      purpose = WithdrawalPurpose.Nursery,
                      withdrawn = seeds(20),
                  )
              )
        }

    publisher.assertEventPublished { event ->
      event is WithdrawalCreatedEvent &&
          event.purpose == WithdrawalPurpose.Other &&
          event.estimatedCount == 130L &&
          event.estimatedWeight == null
    }
    publisher.assertEventPublished { event ->
      event is WithdrawalCreatedEvent &&
          event.purpose == WithdrawalPurpose.Nursery &&
          event.estimatedCount == null &&
          event.estimatedWeight == grams<SeedQuantityModel>(2)
    }
  }

  @Test
  fun `accession updates do not change withdrawal custom purposes`() {
    val accession = create(accessionModel(remaining = grams(100)))
    val accessionId = accession.id!!
    val purposeId =
        insertWithdrawalCustomPurpose(
            isArchived = true,
            name = "Sále",
            type = WithdrawalCustomPurposeType.SeedBank,
        )
    insertSeedbankWithdrawal(
        accessionId = accessionId,
        batchId = null,
        purpose = WithdrawalPurpose.Other,
        customPurposeId = purposeId,
        withdrawnQuantity = BigDecimal(13),
        withdrawnUnitsId = SeedQuantityUnits.Grams,
    )

    val purposeRowsBefore = dslContext.fetch(WITHDRAWAL_CUSTOM_PURPOSES)

    store.updateAndFetch(
        store
            .fetchOneById(accessionId)
            .copy(
                remaining = grams(100),
                subsetCount = 10,
                subsetWeightQuantity = grams(1),
            )
    )

    val withdrawal = store.fetchOneById(accessionId).withdrawals.single()
    assertEquals("Sále", withdrawal.customPurpose, "Custom purpose")
    assertTableEquals(purposeRowsBefore)
  }
}
