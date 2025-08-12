package com.terraformation.backend.nursery.api

import com.terraformation.backend.api.NurseryEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.model.NurseryStats
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@NurseryEndpoint
@RequestMapping("/api/v1/nursery/facilities")
@RestController
class NurseryFacilitiesController(
    private val batchStore: BatchStore,
) {
  @GetMapping("/{facilityId}/summary")
  @Operation(summary = "Gets a summary of the numbers of plants in a nursery.")
  fun getNurserySummary(
      @PathVariable facilityId: FacilityId,
  ): GetNurserySummaryResponsePayload {
    val stats = batchStore.getNurseryStats(facilityId)
    val species = batchStore.getActiveSpecies(facilityId)

    return GetNurserySummaryResponsePayload(NurserySummaryPayload(stats, species))
  }
}

data class NurserySummarySpeciesPayload(
    val id: SpeciesId,
    val scientificName: String,
) {
  constructor(row: SpeciesRow) : this(row.id!!, row.scientificName!!)
}

data class NurserySummaryPayload(
    val activeGrowthQuantity: Long,
    val germinatingQuantity: Long,
    val germinationRate: Int?,
    @Schema(
        description = "Percentage of current and past inventory that was withdrawn due to death.",
        minimum = "0",
        maximum = "100")
    val lossRate: Int?,
    val readyQuantity: Long,
    @ArraySchema(arraySchema = Schema(description = "Species currently present in the nursery."))
    val species: List<NurserySummarySpeciesPayload>,
    @Schema(description = "Total number of plants that have been withdrawn due to death.")
    val totalDead: Long,
    @Schema(description = "Total number of germinated plants currently in inventory.")
    val totalQuantity: Long,
    @Schema(description = "Total number of plants that have been withdrawn in the past.")
    val totalWithdrawn: Long,
) {
  constructor(
      stats: NurseryStats,
      species: List<SpeciesRow>,
  ) : this(
      activeGrowthQuantity = stats.totalActiveGrowth,
      germinatingQuantity = stats.totalGerminating,
      germinationRate = stats.germinationRate,
      lossRate = stats.lossRate,
      readyQuantity = stats.totalReady,
      species = species.map { NurserySummarySpeciesPayload(it) },
      totalDead = stats.totalWithdrawnByPurpose[WithdrawalPurpose.Dead] ?: 0L,
      totalQuantity = stats.totalInventory,
      totalWithdrawn = stats.totalWithdrawn,
  )

  val notReadyQuantity: Long // for backwards compatibility in response payloads
    get() = activeGrowthQuantity
}

data class GetNurserySummaryResponsePayload(val summary: NurserySummaryPayload) :
    SuccessResponsePayload
