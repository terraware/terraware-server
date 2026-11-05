package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.db.nursery.tables.pojos.BatchStageDetailsRow

data class BatchStageDetailsModel(
    val batchId: BatchId,
    val latestObservedQuantity: Int,
    val quantity: Int,
    val stage: BatchStage,
    val timeInStage: StageTimeStats? = null,
)

fun BatchStageDetailsRow.toModel(): BatchStageDetailsModel =
    BatchStageDetailsModel(
        batchId = batchId!!,
        latestObservedQuantity = latestObservedQuantity!!,
        quantity = quantity!!,
        stage = stageId!!,
        timeInStage =
            countedQuantity?.let { countedQuantity ->
              StageTimeStats(
                  asOfTime = timeInStageAsOf!!,
                  cleared = timeInStageCleared!!,
                  countedDays = countedSeedlingDays!!,
                  countedQuantity = countedQuantity,
                  finishedDays = finishedSeedlingDays ?: 0.0,
                  finishedQuantity = finishedQuantity ?: 0.0,
                  inStage = quantity!!,
                  openDays = openSeedlingDays!!,
                  stage = stageId!!,
              )
            },
    )
