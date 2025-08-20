package com.terraformation.backend.nursery.api

import com.terraformation.backend.api.NurseryEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.model.NurseryStats
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@NurseryEndpoint
@RequestMapping("/api/v1/nursery/summary")
@RestController
class NurserySummaryController(
    private val batchStore: BatchStore,
) {
  @GetMapping
  @Operation(
      summary = "Get a summary of the numbers of plants in all the nurseries in an organization."
  )
  fun getOrganizationNurserySummary(
      @RequestParam organizationId: OrganizationId
  ): GetOrganizationNurserySummaryResponsePayload {
    val stats = batchStore.getNurseryStats(organizationId = organizationId)

    return GetOrganizationNurserySummaryResponsePayload(OrganizationNurserySummaryPayload(stats))
  }
}

data class OrganizationNurserySummaryPayload(
    val activeGrowthQuantity: Long,
    val germinatingQuantity: Long,
    val germinationRate: Int?,
    val hardeningOffQuantity: Long,
    @Schema(
        description = "Percentage of current and past inventory that was withdrawn due to death.",
        minimum = "0",
        maximum = "100",
    )
    val lossRate: Int?,
    val readyQuantity: Long,
    @Schema(description = "Total number of plants that have been withdrawn due to death.")
    val totalDead: Long,
    @Schema(description = "Total number of germinated plants currently in inventory.")
    val totalQuantity: Long,
    @Schema(description = "Total number of plants that have been withdrawn in the past.")
    val totalWithdrawn: Long,
) {
  constructor(
      stats: NurseryStats
  ) : this(
      activeGrowthQuantity = stats.totalActiveGrowth,
      germinatingQuantity = stats.totalGerminating,
      germinationRate = stats.germinationRate,
      hardeningOffQuantity = stats.totalHardeningOff,
      lossRate = stats.lossRate,
      readyQuantity = stats.totalReady,
      totalDead = stats.totalWithdrawnByPurpose[WithdrawalPurpose.Dead] ?: 0L,
      totalQuantity = stats.totalInventory,
      totalWithdrawn = stats.totalWithdrawn,
  )

  val notReadyQuantity: Long // for backwards compatibility in response payloads
    get() = activeGrowthQuantity
}

data class GetOrganizationNurserySummaryResponsePayload(
    val summary: OrganizationNurserySummaryPayload
) : SuccessResponsePayload
