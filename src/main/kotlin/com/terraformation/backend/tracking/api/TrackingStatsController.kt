package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.tracking.db.TrackingStatsStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.ws.rs.BadRequestException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/stats")
@RestController
@TrackingEndpoint
class TrackingStatsController(
    private val trackingStatsStore: TrackingStatsStore,
) {
  @GetMapping
  @Operation(summary = "Gets aggregated statistics about planting sites.")
  fun getAggregatedTrackingStats(
      @RequestParam
      @Parameter(description = "Organization ID to summarize. Ignored if projectId is supplied.")
      organizationId: OrganizationId? = null,
      @RequestParam projectId: ProjectId? = null,
  ): TrackingStatsResponsePayload {
    val survivalRate =
        when {
          projectId != null -> trackingStatsStore.getSurvivalRate(projectId)
          organizationId != null -> trackingStatsStore.getSurvivalRate(organizationId)
          else -> throw BadRequestException("Must specify either organizationId or projectId")
        }

    return TrackingStatsResponsePayload(survivalRate = survivalRate)
  }
}

data class TrackingStatsResponsePayload(
    @Schema(
        description =
            "Aggregate survival rate. Not present if there have been no observations " +
                "of the specified planting sites."
    )
    val survivalRate: Int?,
) : SuccessResponsePayload
