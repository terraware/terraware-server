package com.terraformation.backend.statistics.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.PublicEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.statistics.PublicStatisticsModel
import com.terraformation.backend.statistics.db.PublicStatisticsStore
import io.swagger.v3.oas.annotations.Operation
import java.math.BigDecimal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@PublicEndpoint
@RestController
@RequestMapping("/api/v1/public/statistics")
class PublicStatisticsController(private val publicStatisticsStore: PublicStatisticsStore) {
  @ApiResponse200
  @GetMapping
  @Operation(
      summary = "Gets aggregate statistics about the Terraware platform.",
      description =
          "These statistics are aggregated across all organizations and do not require " +
              "authentication. Organizations that are internal to Terraformation are excluded.",
  )
  fun getPublicStatistics(): GetPublicStatisticsResponsePayload {
    return GetPublicStatisticsResponsePayload(
        PublicStatisticsPayload(publicStatisticsStore.fetchStatistics())
    )
  }
}

data class PublicStatisticsPayload(
    val totalOrganizations: Int,
    val totalCountries: Int,
    val totalAreaUnderRestorationHa: BigDecimal,
    val totalSeedsInStorage: Long,
    val totalSeedlingsInNurseries: Long,
    val totalPlantings: Int,
) {
  constructor(
      model: PublicStatisticsModel
  ) : this(
      totalOrganizations = model.totalOrganizations,
      totalCountries = model.totalCountries,
      totalAreaUnderRestorationHa = model.totalAreaUnderRestorationHa,
      totalSeedsInStorage = model.totalSeedsInStorage,
      totalSeedlingsInNurseries = model.totalSeedlingsInNurseries,
      totalPlantings = model.totalPlantings,
  )
}

data class GetPublicStatisticsResponsePayload(val statistics: PublicStatisticsPayload) :
    SuccessResponsePayload
