package com.terraformation.backend.nursery.db

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.BatchId

data class NurseryBatchEventData(
    val batchId: BatchId,
    val batchNumber: String,
    val speciesId: SpeciesId,
    val nurseryName: String,
)
