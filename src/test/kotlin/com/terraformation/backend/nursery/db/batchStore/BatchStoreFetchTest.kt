package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.nursery.BatchSubstrate
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.ExistingBatchModel
import com.terraformation.backend.nursery.model.ExistingWithdrawalModel
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class BatchStoreFetchTest : BatchStoreTest() {
  @Test
  fun `returns model from database row`() {
    val accessionNumber = "2023-01-01"
    val accessionId = insertAccession(facilityId = facilityId, number = accessionNumber)

    val batchId =
        insertBatch(
            BatchesRow(
                accessionId = accessionId,
                germinationStartedDate = LocalDate.of(2023, 5, 15),
                notes = "Notes",
                seedsSownDate = LocalDate.of(2023, 5, 10),
                substrateId = BatchSubstrate.Moss,
                substrateNotes = "Substrate Notes",
                treatmentId = SeedTreatment.Chemical,
                treatmentNotes = "Treatment Notes",
            ),
            batchNumber = "23-2-1-008",
            germinatingQuantity = 64,
            hardeningOffQuantity = 512,
            activeGrowthQuantity = 128,
            readyByDate = LocalDate.of(2023, 6, 7),
            readyQuantity = 256,
            speciesId = speciesId,
        )

    val subLocationId1 = insertSubLocation()
    insertBatchSubLocation()
    val subLocationId2 = insertSubLocation()
    insertBatchSubLocation()

    insertNurseryWithdrawal()
    insertBatchWithdrawal(
        germinatingQuantityWithdrawn = 1,
        activeGrowthQuantityWithdrawn = 2,
        readyQuantityWithdrawn = 4,
        hardeningOffQuantityWithdrawn = 8,
    )
    insertNurseryWithdrawal()
    insertBatchWithdrawal(
        germinatingQuantityWithdrawn = 8,
        activeGrowthQuantityWithdrawn = 16,
        readyQuantityWithdrawn = 32,
        hardeningOffQuantityWithdrawn = 64,
    )

    val expected =
        ExistingBatchModel(
            accessionId = accessionId,
            accessionNumber = accessionNumber,
            addedDate = LocalDate.EPOCH,
            batchNumber = "23-2-1-008",
            facilityId = facilityId,
            germinatingQuantity = 64,
            germinationStartedDate = LocalDate.of(2023, 5, 15),
            hardeningOffQuantity = 512,
            id = batchId,
            latestObservedGerminatingQuantity = 64,
            latestObservedHardeningOffQuantity = 512,
            latestObservedActiveGrowthQuantity = 128,
            latestObservedReadyQuantity = 256,
            latestObservedTime = Instant.EPOCH,
            lossRate = 0,
            notes = "Notes",
            activeGrowthQuantity = 128,
            organizationId = organizationId,
            readyByDate = LocalDate.of(2023, 6, 7),
            readyQuantity = 256,
            seedsSownDate = LocalDate.of(2023, 5, 10),
            speciesId = speciesId,
            subLocationIds = setOf(subLocationId1, subLocationId2),
            substrate = BatchSubstrate.Moss,
            substrateNotes = "Substrate Notes",
            treatment = SeedTreatment.Chemical,
            treatmentNotes = "Treatment Notes",
            totalWithdrawn = 135,
            version = 1,
        )

    val actual = store.fetchOneById(batchId)

    assertEquals(expected, actual)
  }

  @Test
  fun `fetchWithdrawalById populates all fields for withdrawal and undo withdrawal`() {
    insertPlantingSite()
    val plantingSeasonId = insertPlantingSeason()

    every { user.canReadWithdrawal(any()) } returns true

    insertBatch()
    val withdrawalId =
        insertNurseryWithdrawal(
            purpose = WithdrawalPurpose.OutPlant,
            plantingSeasonId = plantingSeasonId,
        )
    insertBatchWithdrawal(readyQuantityWithdrawn = 1)
    val undoWithdrawalId =
        insertNurseryWithdrawal(
            purpose = WithdrawalPurpose.Undo,
            undoesWithdrawalId = withdrawalId,
            plantingSeasonId = plantingSeasonId,
        )
    insertBatchWithdrawal(readyQuantityWithdrawn = -1)

    val withdrawal = store.fetchWithdrawalById(withdrawalId)
    assertEquals(
        ExistingWithdrawalModel(
            batchWithdrawals =
                listOf(
                    BatchWithdrawalModel(
                        batchId = inserted.batchId,
                        germinatingQuantityWithdrawn = 0,
                        activeGrowthQuantityWithdrawn = 0,
                        hardeningOffQuantityWithdrawn = 0,
                        readyQuantityWithdrawn = 1,
                    )
                ),
            facilityId = facilityId,
            id = withdrawalId,
            plantingSeasonId = plantingSeasonId,
            purpose = WithdrawalPurpose.OutPlant,
            withdrawnDate = LocalDate.EPOCH,
            undoneByWithdrawalId = undoWithdrawalId,
        ),
        withdrawal,
        "Initial Withdrawal",
    )

    val undoWithdrawal = store.fetchWithdrawalById(undoWithdrawalId)
    assertEquals(withdrawalId, undoWithdrawal.undoesWithdrawalId, "Undoes")
    assertEquals(
        ExistingWithdrawalModel(
            batchWithdrawals =
                listOf(
                    BatchWithdrawalModel(
                        batchId = inserted.batchId,
                        germinatingQuantityWithdrawn = 0,
                        activeGrowthQuantityWithdrawn = 0,
                        hardeningOffQuantityWithdrawn = 0,
                        readyQuantityWithdrawn = -1,
                    )
                ),
            facilityId = facilityId,
            id = undoWithdrawalId,
            plantingSeasonId = plantingSeasonId,
            purpose = WithdrawalPurpose.Undo,
            withdrawnDate = LocalDate.EPOCH,
            undoesWithdrawalId = withdrawalId,
        ),
        undoWithdrawal,
        "Undo Withdrawal",
    )
  }
}
