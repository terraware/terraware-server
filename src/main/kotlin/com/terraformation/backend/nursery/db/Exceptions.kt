package com.terraformation.backend.nursery.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.EntityStaleException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId

class BatchNotFoundException(val batchId: BatchId) :
    EntityNotFoundException("Seedling batch $batchId not found")

class BatchStaleException(val batchId: BatchId, val requestedVersion: Int) :
    EntityStaleException("Seedling batch $batchId version $requestedVersion out of date")

class BatchInventoryInsufficientException(val batchId: BatchId) :
    IllegalArgumentException("Withdrawal quantity can't be more than remaining quantity")

class CrossOrganizationNurseryTransferNotAllowedException(
    val facilityId: FacilityId,
    val destinationFacilityId: FacilityId
) :
    MismatchedStateException(
        "Cannot transfer from facility $facilityId to facility $destinationFacilityId because " +
            "they are in different organizations")

class WithdrawalNotFoundException(val withdrawalId: WithdrawalId) :
    EntityNotFoundException("Withdrawal $withdrawalId not found")
