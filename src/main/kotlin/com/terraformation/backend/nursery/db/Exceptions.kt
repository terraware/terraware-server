package com.terraformation.backend.nursery.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.nursery.BatchId

class BatchNotFoundException(val batchId: BatchId) :
    EntityNotFoundException("Seedling batch $batchId not found")
