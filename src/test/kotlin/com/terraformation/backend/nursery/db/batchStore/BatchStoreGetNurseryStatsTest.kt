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
            activeGrowthQuantity = 2,
            readyQuantity = 3,
            hardeningOffQuantity = 4,
            totalGerminated = 100,
            totalGerminationCandidates = 200,
            totalLost = 300,
            totalLossCandidates = 400)
    val batchId2 =
        insertBatch(
            facilityId = facilityId,
            speciesId = speciesId2,
            germinatingQuantity = 4,
            activeGrowthQuantity = 5,
            readyQuantity = 6,
            hardeningOffQuantity = 7,
            totalLost = 500,
            totalLossCandidates = 600)

    insertNurseryWithdrawal(facilityId = facilityId, purpose = WithdrawalPurpose.OutPlant)
    insertBatchWithdrawal(
        batchId = batchId1,
        germinatingQuantityWithdrawn = 10,
        activeGrowthQuantityWithdrawn = 11,
        readyQuantityWithdrawn = 12,
        hardeningOffQuantityWithdrawn = 13)
    insertBatchWithdrawal(
        batchId = batchId2,
        germinatingQuantityWithdrawn = 13,
        activeGrowthQuantityWithdrawn = 14,
        readyQuantityWithdrawn = 15,
        hardeningOffQuantityWithdrawn = 16)

    insertNurseryWithdrawal(facilityId = facilityId, purpose = WithdrawalPurpose.OutPlant)
    insertBatchWithdrawal(
        batchId = batchId1,
        germinatingQuantityWithdrawn = 16,
        activeGrowthQuantityWithdrawn = 17,
        readyQuantityWithdrawn = 18,
        hardeningOffQuantityWithdrawn = 19)

    insertNurseryWithdrawal(facilityId = facilityId, purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(
        batchId = batchId1,
        germinatingQuantityWithdrawn = 19,
        activeGrowthQuantityWithdrawn = 20,
        readyQuantityWithdrawn = 21,
        hardeningOffQuantityWithdrawn = 22)
    insertBatchWithdrawal(
        batchId = batchId2,
        germinatingQuantityWithdrawn = 22,
        activeGrowthQuantityWithdrawn = 23,
        readyQuantityWithdrawn = 24,
        hardeningOffQuantityWithdrawn = 25)

    insertNurseryWithdrawal(facilityId = facilityId, purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(
        batchId = batchId1,
        germinatingQuantityWithdrawn = 25,
        activeGrowthQuantityWithdrawn = 26,
        readyQuantityWithdrawn = 27,
        hardeningOffQuantityWithdrawn = 28)

    insertBatch(
        facilityId = otherNurseryId,
        speciesId = speciesId,
        germinatingQuantity = 7,
        activeGrowthQuantity = 8,
        readyQuantity = 9,
        hardeningOffQuantity = 10,
        totalGerminated = 700,
        totalGerminationCandidates = 800,
        totalLost = 900,
        totalLossCandidates = 1000)
    insertNurseryWithdrawal(facilityId = otherNurseryId, purpose = WithdrawalPurpose.OutPlant)
    insertBatchWithdrawal(
        germinatingQuantityWithdrawn = 28,
        activeGrowthQuantityWithdrawn = 29,
        readyQuantityWithdrawn = 30,
        hardeningOffQuantityWithdrawn = 31)

    insertNurseryWithdrawal(facilityId = otherNurseryId, purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(
        germinatingQuantityWithdrawn = 31,
        activeGrowthQuantityWithdrawn = 32,
        readyQuantityWithdrawn = 33,
        hardeningOffQuantityWithdrawn = 34)

    // Withdrawal that is undone should not affect stats.
    insertNurseryWithdrawal(facilityId = facilityId, purpose = WithdrawalPurpose.Dead)
    insertBatchWithdrawal(
        batchId = batchId1,
        germinatingQuantityWithdrawn = 34,
        activeGrowthQuantityWithdrawn = 35,
        readyQuantityWithdrawn = 36,
        hardeningOffQuantityWithdrawn = 37)
    insertNurseryWithdrawal(
        facilityId = facilityId,
        purpose = WithdrawalPurpose.Undo,
        undoesWithdrawalId = inserted.withdrawalId)
    insertBatchWithdrawal(
        batchId = batchId1,
        germinatingQuantityWithdrawn = -34,
        activeGrowthQuantityWithdrawn = -35,
        readyQuantityWithdrawn = -36,
        hardeningOffQuantityWithdrawn = -37)

    // Batch in other organization's facility should not affect stats.
    insertOrganization()
    insertFacility(type = FacilityType.Nursery)
    insertSpecies()
    insertBatch(
        germinatingQuantity = 1000,
        activeGrowthQuantity = 2000,
        readyQuantity = 3000,
        hardeningOffQuantity = 4000,
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
            totalActiveGrowth = 2 + 5,
            totalHardeningOff = 4 + 7,
            totalReady = 3 + 6,
            totalWithdrawnByPurpose =
                mapOf(
                    WithdrawalPurpose.Dead to 20L + 21L + 22L + 23L + 24L + 25L + 26L + 27L + 28L,
                    WithdrawalPurpose.OutPlant to
                        11L + 12L + 13L + 14L + 15L + 16L + 17L + 18L + 19L,
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
            totalActiveGrowth = 2 + 5 + 8,
            totalHardeningOff = 4 + 7 + 10,
            totalReady = 3 + 6 + 9,
            totalWithdrawnByPurpose =
                mapOf(
                    WithdrawalPurpose.Dead to
                        20L + 21L + 22L + 23L + 24L + 25L + 26L + 27L + 28L + 32L + 33L + 34L,
                    WithdrawalPurpose.OutPlant to
                        11L + 12L + 13L + 14L + 15L + 16L + 17L + 18L + 19L + 29L + 30L + 31L,
                    WithdrawalPurpose.NurseryTransfer to 0L,
                    WithdrawalPurpose.Other to 0L,
                )),
        store.getNurseryStats(organizationId = organizationId),
        "Stats for organization")
  }
}
