package com.terraformation.backend.nursery.api

import com.terraformation.backend.api.NurseryEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.model.SpeciesSummary
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
  fun getSpeciesSummary(
      @PathVariable("speciesId") speciesId: SpeciesId
  ): GetSpeciesSummaryResponsePayload {
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
    val germinatingQuantity: Long,
    val germinationRate: Int?,
    @Schema(
        description = "Percentage of current and past inventory that was withdrawn due to death.",
        minimum = "0",
        maximum = "100")
    val lossRate: Int?,
    val nurseries: List<SpeciesSummaryNurseryPayload>,
    val readyQuantity: Long,
    val speciesId: SpeciesId,
    @Schema(
        description = "Total number of germinated plants that have been withdrawn due to death.")
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
      germinatingQuantity = summary.germinatingQuantity,
      germinationRate = summary.germinationRate,
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

data class GetSpeciesSummaryResponsePayload(val summary: SpeciesSummaryPayload) :
    SuccessResponsePayload
