package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import java.time.LocalDate

/**
 * The number of seedlings of each type that came from a single batch as part of a withdrawal. There
 * can be more than one of these per withdrawal.
 */
data class BatchWithdrawalModel(
    val batchId: BatchId,
    val destinationBatchId: BatchId? = null,
    val germinatingQuantityWithdrawn: Int,
    val notReadyQuantityWithdrawn: Int,
    val readyQuantityWithdrawn: Int,
) {
  init {
    if (germinatingQuantityWithdrawn < 0 ||
        notReadyQuantityWithdrawn < 0 ||
        readyQuantityWithdrawn < 0) {
      throw IllegalArgumentException("Withdrawal quantities may not be negative")
    }
  }
}

/**
 * Information about a withdrawal as a whole.
 *
 * @param ID This class can represent a withdrawal we loaded from the database (in which case [ID]
 * will be non-nullable) or a new withdrawal we want to insert (in which case [ID] will be a
 * null-only type). Use [ExistingWithdrawalModel] and [NewWithdrawalModel] rather than specifying a
 * type parameter at usage sites.
 */
data class WithdrawalModel<ID : WithdrawalId?>(
    val batchWithdrawals: List<BatchWithdrawalModel>,
    val destinationFacilityId: FacilityId? = null,
    val facilityId: FacilityId,
    val id: ID,
    val notes: String? = null,
    val purpose: WithdrawalPurpose,
    val withdrawnDate: LocalDate,
) {
  init {
    if (batchWithdrawals.isEmpty()) {
      throw IllegalArgumentException("Withdrawals must come from at least one batch")
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
        notReadyQuantityWithdrawn = notReadyQuantityWithdrawn!!,
        readyQuantityWithdrawn = readyQuantityWithdrawn!!,
    )

fun WithdrawalsRow.toModel(batchWithdrawals: List<BatchWithdrawalsRow>): ExistingWithdrawalModel =
    ExistingWithdrawalModel(
        batchWithdrawals = batchWithdrawals.map { it.toModel() },
        destinationFacilityId = destinationFacilityId,
        facilityId = facilityId!!,
        id = id!!,
        notes = notes,
        purpose = purposeId!!,
        withdrawnDate = withdrawnDate!!,
    )
