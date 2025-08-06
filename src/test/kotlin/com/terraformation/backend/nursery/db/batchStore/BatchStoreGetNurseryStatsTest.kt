package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.nursery.model.NurseryStats
import io.mockk.every
import org.junit.jupiter.api.Test

internal class BatchStoreGetNurseryStatsTest : BatchStoreTest() {
  @Test
  fun `rolls up multiple species and withdrawals for facility or organization`() {
    every { user.canReadFacility(any()) } returns true
    every { user.canReadOrganization(any()) } returns true

    val otherNurseryId = insertFacility(type = FacilityType.Nursery)
    val speciesId2 = insertSpecies(scientificName = "Second species")

    val batchId1 =
        insertBatch(
            facilityId = facilityId,
            speciesId = speciesId,
            germinatingQuantity = 1,
            notReadyQuantity = 2,
            readyQuantity = 3,
            totalGerminated = 100,
            totalGerminationCandidates = 200,
            totalLost = 300,
            totalLossCandidates = 400)
    val batchId2 =
        insertBatch(
            facilityId = facilityId,
            speciesId = speciesId2,
            germinatingQuantity = 4,
            notReadyQuantity = 5,
            readyQuantity = 6,
            totalLost = 500,
            totalLossCandidates = 600)

    insertNurseryWithdrawal(facilityId = facilityId, purpose = WithdrawalPurpose.OutPlant)
    insertBatchWithdrawal(
        batchId = batchId1,
        germinatingQuantityWithdrawn = 10,
        notReadyQuantityWithdrawn = 11,
        readyQuantityWithdrawn = 12)
    insertBatchWithdrawal(
        batchId = batchId2,
        germinatingQuantityWithdrawn = 13,
        notReadyQuantityWithdrawn = 14,
        readyQuantityWithdrawn = 15)

    insertNurseryWithdrawal(facilityId = facilityId, purpose = WithdrawalPurpose.OutPlant)
    insertBatchWithdrawal(
        batchId = batchId1,
        germinatingQuantityWithdrawn = 16,
        notReadyQuantityWithdrawn = 17,
        readyQuantityWithdrawn = 18)

    insertNurseryWithdrawal(facilityId = facilityId, purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(
        batchId = batchId1,
        germinatingQuantityWithdrawn = 19,
        notReadyQuantityWithdrawn = 20,
        readyQuantityWithdrawn = 21)
    insertBatchWithdrawal(
        batchId = batchId2,
        germinatingQuantityWithdrawn = 22,
        notReadyQuantityWithdrawn = 23,
        readyQuantityWithdrawn = 24)

    insertNurseryWithdrawal(facilityId = facilityId, purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(
        batchId = batchId1,
        germinatingQuantityWithdrawn = 25,
        notReadyQuantityWithdrawn = 26,
        readyQuantityWithdrawn = 27)

    insertBatch(
        facilityId = otherNurseryId,
        speciesId = speciesId,
        germinatingQuantity = 7,
        notReadyQuantity = 8,
        readyQuantity = 9,
        totalGerminated = 700,
        totalGerminationCandidates = 800,
        totalLost = 900,
        totalLossCandidates = 1000)
    insertNurseryWithdrawal(facilityId = otherNurseryId, purpose = WithdrawalPurpose.OutPlant)
    insertBatchWithdrawal(
        germinatingQuantityWithdrawn = 28,
        notReadyQuantityWithdrawn = 29,
        readyQuantityWithdrawn = 30)

    insertNurseryWithdrawal(facilityId = otherNurseryId, purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(
        germinatingQuantityWithdrawn = 31,
        notReadyQuantityWithdrawn = 32,
        readyQuantityWithdrawn = 33)

    // Withdrawal that is undone should not affect stats.
    insertNurseryWithdrawal(facilityId = facilityId, purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(
        batchId = batchId1,
        germinatingQuantityWithdrawn = 34,
        notReadyQuantityWithdrawn = 35,
        readyQuantityWithdrawn = 36)
    insertNurseryWithdrawal(
        facilityId = facilityId,
        purpose = WithdrawalPurpose.Undo,
        undoesWithdrawalId = inserted.withdrawalId)
    insertBatchWithdrawal(
        batchId = batchId1,
        germinatingQuantityWithdrawn = -34,
        notReadyQuantityWithdrawn = -35,
        readyQuantityWithdrawn = -36)

    // Batch in other organization's facility should not affect stats.
    insertOrganization()
    insertFacility(type = FacilityType.Nursery)
    insertSpecies()
    insertBatch(
        germinatingQuantity = 1000,
        notReadyQuantity = 2000,
        readyQuantity = 3000,
        totalGerminated = 10000,
        totalGerminationCandidates = 20000,
        totalLost = 30000,
        totalLossCandidates = 40000)

    assertJsonEquals(
        NurseryStats(
            facilityId = facilityId,
            germinationRate = 50,
            lossRate = 80,
            totalGerminating = 1 + 4,
            totalNotReady = 2 + 5,
            totalHardeningOff = 0,
            totalReady = 3 + 6,
            totalWithdrawnByPurpose =
                mapOf(
                    WithdrawalPurpose.Dead to 20L + 21L + 23L + 24L + 26L + 27L,
                    WithdrawalPurpose.OutPlant to 11L + 12L + 14L + 15L + 17L + 18L,
                    WithdrawalPurpose.NurseryTransfer to 0L,
                    WithdrawalPurpose.Other to 0L,
                )),
        store.getNurseryStats(facilityId),
        "Stats for single facility")

    assertJsonEquals(
        NurseryStats(
            facilityId = null,
            germinationRate = 80,
            lossRate = 85,
            totalGerminating = 1 + 4 + 7,
            totalNotReady = 2 + 5 + 8,
            totalHardeningOff = 0,
            totalReady = 3 + 6 + 9,
            totalWithdrawnByPurpose =
                mapOf(
                    WithdrawalPurpose.Dead to 20L + 21L + 23L + 24L + 26L + 27L + 32L + 33L,
                    WithdrawalPurpose.OutPlant to 11L + 12L + 14L + 15L + 17L + 18L + 29L + 30L,
                    WithdrawalPurpose.NurseryTransfer to 0L,
                    WithdrawalPurpose.Other to 0L,
                )),
        store.getNurseryStats(organizationId = organizationId),
        "Stats for organization")
  }
}
