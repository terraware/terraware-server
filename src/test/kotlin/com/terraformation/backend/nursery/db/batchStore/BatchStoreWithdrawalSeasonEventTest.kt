package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.i18n.TimeZones
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonWithdrawalCreatedEvent
import io.mockk.every
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class BatchStoreWithdrawalSeasonEventTest : BatchStoreTest() {
  private lateinit var batchId: BatchId
  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var plantingSeasonId: PlantingSeasonId

  @BeforeEach
  fun setUpSeasonAndBatch() {
    batchId =
        insertBatch(
            speciesId = speciesId,
            germinatingQuantity = 10,
            activeGrowthQuantity = 20,
            readyQuantity = 30,
            hardeningOffQuantity = 40,
        )
    plantingSiteId = insertPlantingSite()
    plantingSeasonId = insertPlantingSeason()

    every { user.canReadWithdrawal(any()) } returns true
  }

  @Test
  fun `publishes event when withdrawal has a planting season`() {
    val withdrawalId = withdraw(plantingSeasonId = plantingSeasonId)

    eventPublisher.assertEventPublished(
        PlantingSeasonWithdrawalCreatedEvent(
            facilityId = facilityId,
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            plantingSiteId = plantingSiteId,
            withdrawalDate = LocalDate.EPOCH,
            withdrawalId = withdrawalId,
        )
    )
  }

  @Test
  fun `publishes event when undoing a withdrawal with a planting season`() {
    val withdrawalId = withdraw(plantingSeasonId = plantingSeasonId)
    eventPublisher.clear()

    clock.instant = clock.instant().plusSeconds(86400)

    val undo = store.undoWithdrawal(withdrawalId)

    eventPublisher.assertEventPublished(
        PlantingSeasonWithdrawalCreatedEvent(
            facilityId = facilityId,
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            plantingSiteId = plantingSiteId,
            withdrawalDate = LocalDate.EPOCH.plusDays(1),
            withdrawalId = undo.id,
        )
    )
  }

  @Test
  fun `does not publish event when withdrawal has no planting season`() {
    withdraw(plantingSeasonId = null, purpose = WithdrawalPurpose.Other)

    eventPublisher.assertEventNotPublished<PlantingSeasonWithdrawalCreatedEvent>()
  }

  private fun withdraw(
      plantingSeasonId: PlantingSeasonId? = null,
      purpose: WithdrawalPurpose = WithdrawalPurpose.OutPlant,
  ) =
      store
          .withdraw(
              NewWithdrawalModel(
                  batchWithdrawals =
                      listOf(
                          BatchWithdrawalModel(
                              batchId = batchId,
                              germinatingQuantityWithdrawn = 1,
                              activeGrowthQuantityWithdrawn = 2,
                              readyQuantityWithdrawn = 3,
                              hardeningOffQuantityWithdrawn = 4,
                          )
                      ),
                  facilityId = facilityId,
                  id = null,
                  plantingSeasonId = plantingSeasonId,
                  purpose = purpose,
                  withdrawnDate = LocalDate.ofInstant(clock.instant(), TimeZones.UTC),
              )
          )
          .id
}
