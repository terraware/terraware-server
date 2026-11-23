package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingStratumModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.StratumPlotCounts
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.math.RoundingMode
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/sites")
@RestController
@TrackingEndpoint
class PlotSettingsController(private val plantingSiteStore: PlantingSiteStore) {
  @GetMapping("/{id}/plotSettings")
  @Operation(summary = "Gets a planting site's plot selection settings.")
  @ApiResponse200
  @ApiResponse404
  fun getPlotSettings(@PathVariable id: PlantingSiteId): PlotSettingsResponsePayload =
      PlotSettingsResponsePayload(plantingSiteStore.fetchSiteById(id, PlantingSiteDepth.Stratum))
}

data class PlotSettingsResponsePayload(
    val defaultPlotPrecisionLevel: PlotPrecisionLevel,
    val strata: List<StratumPlotSettingsPayload>,
) : SuccessResponsePayload {
  constructor(
      model: ExistingPlantingSiteModel
  ) : this(
      defaultPlotPrecisionLevel = model.defaultPlotPrecisionLevel,
      strata = model.strata.map { StratumPlotSettingsPayload(it) },
  )
}

data class StratumPlotSettingsPayload(
    val stratumId: StratumId,
    val name: String,
    val settings: List<PlotSelectionSettingPayload>,
) {
  constructor(
      model: ExistingStratumModel
  ) : this(
      stratumId = model.id,
      name = model.name,
      settings =
          ObservationType.entries.map { PlotSelectionSettingPayload(it, model.plotCounts(it)) },
  )
}

data class PlotSelectionSettingPayload(
    val observationType: ObservationType,
    val mode: PlotSelectionMode,
    val plotPrecisionLevel: PlotPrecisionLevel?,
    @Schema(description = "Confidence percentage, or null for a nonstandard stored Student's t.")
    val confidenceLevel: Int?,
    val targetMarginOfError: BigDecimal,
    @Schema(description = "Variability percentage rounded to one decimal place.")
    val variability: BigDecimal,
    val numPermanentPlots: Int,
    val numTemporaryPlots: Int,
    @Schema(description = "Unrounded expected precision percentage, or null if unavailable.")
    val expectedPrecision: Double?,
) {
  constructor(
      type: ObservationType,
      counts: StratumPlotCounts,
  ) : this(
      observationType = type,
      mode = counts.mode,
      plotPrecisionLevel = counts.plotPrecisionLevel,
      confidenceLevel = counts.confidenceLevel?.percent,
      targetMarginOfError = counts.errorMargin,
      variability = counts.variability.setScale(1, RoundingMode.HALF_UP),
      numPermanentPlots = counts.numPermanentPlots,
      numTemporaryPlots = counts.numTemporaryPlots,
      expectedPrecision = counts.expectedPrecision,
  )
}
