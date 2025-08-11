package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.tracking.DeliveryId
import java.time.LocalDate

/**
 * The number of seedlings of each type that came from a single batch as part of a withdrawal. There
 * can be more than one of these per withdrawal.
 */
data class BatchWithdrawalModel(
    val batchId: BatchId,
    val destinationBatchId: BatchId? = null,
    val germinatingQuantityWithdrawn: Int,
    val hardeningOffQuantityWithdrawn: Int = 0,
    val notReadyQuantityWithdrawn: Int,
    val readyQuantityWithdrawn: Int,
) {
  val totalWithdrawn: Int
    get() =
        germinatingQuantityWithdrawn +
            notReadyQuantityWithdrawn +
            hardeningOffQuantityWithdrawn +
            readyQuantityWithdrawn
}

/**
 * Information about a withdrawal as a whole.
 *
 * @param ID This class can represent a withdrawal we loaded from the database (in which case [ID]
 *   will be non-nullable) or a new withdrawal we want to insert (in which case [ID] will be a
 *   null-only type). Use [ExistingWithdrawalModel] and [NewWithdrawalModel] rather than specifying
 *   a type parameter at usage sites.
 */
data class WithdrawalModel<ID : WithdrawalId?>(
    val batchWithdrawals: List<BatchWithdrawalModel>,
    val deliveryId: DeliveryId? = null,
    val destinationFacilityId: FacilityId? = null,
    val facilityId: FacilityId,
    val id: ID,
    val notes: String? = null,
    val purpose: WithdrawalPurpose,
    val withdrawnDate: LocalDate,
    val undoesWithdrawalId: WithdrawalId? = null,
    val undoneByWithdrawalId: WithdrawalId? = null,
) {
  init {
    if (batchWithdrawals.isEmpty()) {
      throw IllegalArgumentException("Withdrawals must come from at least one batch")
    }

    if (undoesWithdrawalId != null && purpose != WithdrawalPurpose.Undo ||
        undoesWithdrawalId == null && purpose == WithdrawalPurpose.Undo) {
      throw IllegalArgumentException("Must specify original withdrawal ID if purpose is Undo")
    }
  }
}

/** A withdrawal that hasn't been inserted into the database yet. */
typealias NewWithdrawalModel = WithdrawalModel<Nothing?>

/** A withdrawal that was loaded from the database. */
typealias ExistingWithdrawalModel = WithdrawalModel<WithdrawalId>

fun BatchWithdrawalsRow.toModel(): BatchWithdrawalModel =
    BatchWithdrawalModel(
        batchId = batchId!!,
        destinationBatchId = destinationBatchId,
        germinatingQuantityWithdrawn = germinatingQuantityWithdrawn!!,
        hardeningOffQuantityWithdrawn = hardeningOffQuantityWithdrawn!!,
        notReadyQuantityWithdrawn = activeGrowthQuantityWithdrawn!!,
        readyQuantityWithdrawn = readyQuantityWithdrawn!!,
    )

fun WithdrawalsRow.toModel(
    batchWithdrawals: List<BatchWithdrawalsRow>,
    undoneByWithdrawalId: WithdrawalId? = null
): ExistingWithdrawalModel =
    ExistingWithdrawalModel(
        batchWithdrawals = batchWithdrawals.map { it.toModel() },
        destinationFacilityId = destinationFacilityId,
        facilityId = facilityId!!,
        id = id!!,
        notes = notes,
        purpose = purposeId!!,
        withdrawnDate = withdrawnDate!!,
        undoesWithdrawalId = undoesWithdrawalId,
        undoneByWithdrawalId = undoneByWithdrawalId,
    )
