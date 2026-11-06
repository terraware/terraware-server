package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.nursery.BatchStage

data class SpeciesSummary(
    val activeGrowthQuantity: Long,
    val averageTimeInStage: Map<BatchStage, SpeciesStageTimeModel>,
    val germinatingQuantity: Long,
    val germinationRate: Int?,
    val hardeningOffQuantity: Long,
    val lossRate: Int?,
    val nurseries: List<FacilitiesRow>,
    val readyQuantity: Long,
    val speciesId: SpeciesId,
    val totalDead: Long,
    val totalQuantity: Long,
    val totalWithdrawn: Long,
)
