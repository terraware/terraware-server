package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.db.nursery.tables.pojos.BatchStageDetailsRow

data class BatchStageDetailsModel(
    val batchId: BatchId,
    val latestObservedQuantity: Int,
    val quantity: Int,
    val stage: BatchStage,
)

fun BatchStageDetailsRow.toModel(): BatchStageDetailsModel =
    BatchStageDetailsModel(
        batchId = batchId!!,
        latestObservedQuantity = latestObservedQuantity!!,
        quantity = quantity!!,
        stage = stageId!!,
    )
