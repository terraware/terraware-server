package com.terraformation.backend.nursery.api

import com.terraformation.backend.api.NurseryEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.model.SpeciesStageTimeModel
import com.terraformation.backend.nursery.model.SpeciesSummary
import com.terraformation.backend.nursery.model.StageTimeCalculator
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@NurseryEndpoint
@RequestMapping("/api/v1/nursery/species")
@RestController
class SpeciesController(
    private val batchStore: BatchStore,
) {
  @GetMapping("/{speciesId}/summary")
  @Operation(summary = "Gets a summary of the numbers of plants of each species in all nurseries.")
  fun getSpeciesSummary(@PathVariable speciesId: SpeciesId): GetSpeciesSummaryResponsePayload {
    val summary = batchStore.getSpeciesSummary(speciesId)
    return GetSpeciesSummaryResponsePayload(SpeciesSummaryPayload(summary))
  }
}

data class SpeciesSummaryNurseryPayload(
    val facilityId: FacilityId,
    val name: String,
) {
  constructor(row: FacilitiesRow) : this(row.id!!, row.name!!)
}

data class SpeciesSummaryPayload(
    val activeGrowthQuantity: Long,
    @Schema(
        description = "Time in each nursery stage, counting only seedlings that finished the stage."
    )
    val averageTimeInStage: SpeciesTimeInStagePayload,
    val germinatingQuantity: Long,
    val germinationRate: Int?,
    val hardeningOffQuantity: Long,
    @Schema(
        description = "Percentage of current and past inventory that was withdrawn due to death.",
        minimum = "0",
        maximum = "100",
    )
    val lossRate: Int?,
    val nurseries: List<SpeciesSummaryNurseryPayload>,
    val readyQuantity: Long,
    val speciesId: SpeciesId,
    @Schema(
        description = "Total number of germinated plants that have been withdrawn due to death."
    )
    val totalDead: Long,
    @Schema(description = "Total number of germinated plants currently in inventory.")
    val totalQuantity: Long,
    @Schema(description = "Total number of germinated plants that have been withdrawn in the past.")
    val totalWithdrawn: Long,
) {
  constructor(
      summary: SpeciesSummary
  ) : this(
      activeGrowthQuantity = summary.activeGrowthQuantity,
      averageTimeInStage = SpeciesTimeInStagePayload.of(summary.averageTimeInStage),
      germinatingQuantity = summary.germinatingQuantity,
      germinationRate = summary.germinationRate,
      hardeningOffQuantity = summary.hardeningOffQuantity,
      lossRate = summary.lossRate,
      nurseries = summary.nurseries.map { SpeciesSummaryNurseryPayload(it) },
      readyQuantity = summary.readyQuantity,
      speciesId = summary.speciesId,
      totalDead = summary.totalDead,
      totalQuantity = summary.totalQuantity,
      totalWithdrawn = summary.totalWithdrawn,
  )

  val notReadyQuantity: Long // for backwards compatibility in response payloads
    get() = activeGrowthQuantity
}

data class SpeciesStageTimePayload(
    @Schema(description = "Number of batches with seedlings that finished this stage.")
    val batchCount: Int,
    @Schema(
        description =
            "Seedling-weighted average days in this stage, counting only seedlings that finished " +
                "the stage, rounded to the nearest day. 0 means less than one day. Null when " +
                "fewer than 3 batches contribute."
    )
    val days: Int?,
    @Schema(
        description =
            "Upper quartile of per-batch average days for seedlings that finished the stage, " +
                "rounded to the nearest day. 0 means less than one day. Null when fewer than " +
                "5 batches have at least 10 finished seedlings or days is null."
    )
    val typicalMaxDays: Int?,
    @Schema(
        description =
            "Lower quartile of per-batch average days for seedlings that finished the stage, " +
                "rounded to the nearest day. 0 means less than one day. Null when fewer than " +
                "5 batches have at least 10 finished seedlings or days is null."
    )
    val typicalMinDays: Int?,
    @Schema(description = "Number of batches with at least 10 seedlings that finished this stage.")
    val typicalRangeBatchCount: Int,
) {
  constructor(
      model: SpeciesStageTimeModel
  ) : this(
      batchCount = model.batchCount,
      days = StageTimeCalculator.roundDays(model.averageDays),
      typicalMaxDays = StageTimeCalculator.roundDays(model.typicalMaxDays),
      typicalMinDays = StageTimeCalculator.roundDays(model.typicalMinDays),
      typicalRangeBatchCount = model.typicalRangeBatchCount,
  )
}

data class SpeciesTimeInStagePayload(
    val germinating: SpeciesStageTimePayload,
    val activeGrowth: SpeciesStageTimePayload,
    val hardeningOff: SpeciesStageTimePayload,
    val ready: SpeciesStageTimePayload,
) {
  companion object {
    fun of(stats: Map<BatchStage, SpeciesStageTimeModel>): SpeciesTimeInStagePayload {
      fun payload(stage: BatchStage) = SpeciesStageTimePayload(stats.getValue(stage))

      return SpeciesTimeInStagePayload(
          activeGrowth = payload(BatchStage.ActiveGrowth),
          germinating = payload(BatchStage.Germinating),
          hardeningOff = payload(BatchStage.HardeningOff),
          ready = payload(BatchStage.Ready),
      )
    }
  }
}

data class GetSpeciesSummaryResponsePayload(val summary: SpeciesSummaryPayload) :
    SuccessResponsePayload
