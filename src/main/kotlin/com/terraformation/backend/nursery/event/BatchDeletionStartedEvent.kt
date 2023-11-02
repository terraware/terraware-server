package com.terraformation.backend.nursery.event

import com.terraformation.backend.db.nursery.BatchId

/** Published when a batch is about to be deleted from the database. */
data class BatchDeletionStartedEvent(val batchId: BatchId)
