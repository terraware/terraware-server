package com.terraformation.backend.nursery.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.EntityStaleException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId

class BatchInventoryInsufficientException(val batchId: BatchId) :
    IllegalArgumentException("Withdrawal quantity can't be more than remaining quantity")

class BatchNotFoundException(val batchId: BatchId) :
    EntityNotFoundException("Seedling batch $batchId not found")

class BatchPhaseReversalNotAllowedException(val batchId: BatchId) :
    IllegalArgumentException("Cannot move quantity from further phase for batch $batchId")

class BatchStaleException(val batchId: BatchId, val requestedVersion: Int) :
    EntityStaleException("Seedling batch $batchId version $requestedVersion out of date")

class CrossOrganizationNurseryTransferNotAllowedException(
    val facilityId: FacilityId,
    val destinationFacilityId: FacilityId,
) :
    MismatchedStateException(
        "Cannot transfer from facility $facilityId to facility $destinationFacilityId because " +
            "they are in different organizations"
    )

class UndoOfNurseryTransferNotAllowedException(val withdrawalId: WithdrawalId) :
    MismatchedStateException("Cannot undo nursery transfer withdrawal $withdrawalId")

class UndoOfUndoNotAllowedException(val withdrawalId: WithdrawalId) :
    MismatchedStateException(
        "Cannot undo withdrawal $withdrawalId that was an undo of another withdrawal"
    )

class WithdrawalAlreadyUndoneException(val withdrawalId: WithdrawalId) :
    MismatchedStateException("Withdrawal $withdrawalId has already been undone")

class WithdrawalNotFoundException(val withdrawalId: WithdrawalId) :
    EntityNotFoundException("Withdrawal $withdrawalId not found")
