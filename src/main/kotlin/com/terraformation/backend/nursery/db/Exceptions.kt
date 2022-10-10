package com.terraformation.backend.nursery.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.EntityStaleException
import com.terraformation.backend.db.nursery.BatchId

class BatchNotFoundException(val batchId: BatchId) :
    EntityNotFoundException("Seedling batch $batchId not found")

class BatchStaleException(val batchId: BatchId, val requestedVersion: Int) :
    EntityStaleException("Seedling batch $batchId version $requestedVersion out of date")

class BatchInventoryInsufficientException(val batchId: BatchId) :
    IllegalArgumentException("Withdrawal quantity can't be more than remaining quantity")
