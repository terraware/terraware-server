package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse400
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.tracking.db.ObservationPlotStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.db.StratumNotFoundException
import com.terraformation.backend.tracking.model.ConfidenceLevel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingStratumModel
import com.terraformation.backend.tracking.model.PlaceablePlotCounts
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlotSelectionSetting
import com.terraformation.backend.tracking.model.StratumPlotAllocator
import com.terraformation.backend.tracking.model.StratumPlotCounts
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.math.RoundingMode
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/sites")
@RestController
@TrackingEndpoint
class PlotSettingsController(
    private val observationPlotStore: ObservationPlotStore,
    private val plantingSiteStore: PlantingSiteStore,
) {
  @PostMapping("/{id}/plotSettings/recommendations/accept")
  @Operation(summary = "Accepts the current plot count recommendation for a stratum.")
  @ApiResponse200
  @ApiResponse404
  @ApiResponse409
  fun acceptPlotCountRecommendation(
      @PathVariable id: PlantingSiteId,
      @RequestBody payload: ResolvePlotCountRecommendationRequestPayload,
  ): SuccessResponsePayload {
    plantingSiteStore.acceptPlotCountRecommendation(id, payload.stratumId, payload.observationId)
    return SimpleSuccessResponsePayload()
  }

  @PostMapping("/{id}/plotSettings/recommendations/dismiss")
  @Operation(summary = "Dismisses the current plot count recommendation for a stratum.")
  @ApiResponse200
  @ApiResponse404
  @ApiResponse409
  fun dismissPlotCountRecommendation(
      @PathVariable id: PlantingSiteId,
      @RequestBody payload: ResolvePlotCountRecommendationRequestPayload,
  ): SuccessResponsePayload {
    plantingSiteStore.dismissPlotCountRecommendation(id, payload.stratumId, payload.observationId)
    return SimpleSuccessResponsePayload()
  }

  @PostMapping("/{id}/plotSettings/preview")
  @Operation(summary = "Previews plot settings and precision levels without saving changes.")
  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @ApiResponse409
  fun previewPlotSettings(
      @PathVariable id: PlantingSiteId,
      @RequestBody payload: UpdatePlotSettingsRequestPayload,
  ): PlotSettingsPreviewResponsePayload {
    plantingSiteStore.requirePlotSettingsEditable(id)

    val site = plantingSiteStore.fetchSiteById(id, PlantingSiteDepth.Plot)
    val proposed = payload.toSettings(site)
    val observedPermanentPlotIds =
        site.gridOrigin?.let { observationPlotStore.fetchObservedPermanentPlotIds(id) }.orEmpty()
    val placeable =
        StratumPlotAllocator.countPlaceablePlotsForPreview(site, proposed, observedPermanentPlotIds)
    return PlotSettingsPreviewResponsePayload(site, proposed, placeable)
  }

  @GetMapping("/{id}/plotSettings")
  @Operation(summary = "Gets a planting site's plot selection settings.")
  @ApiResponse200
  @ApiResponse404
  fun getPlotSettings(@PathVariable id: PlantingSiteId): PlotSettingsResponsePayload {
    val site = plantingSiteStore.fetchSiteById(id, PlantingSiteDepth.Plot)
    val observedPermanentPlotIds =
        site.gridOrigin?.let { observationPlotStore.fetchObservedPermanentPlotIds(id) }.orEmpty()
    val placeable = StratumPlotAllocator.countPlaceablePlots(site, observedPermanentPlotIds)
    return PlotSettingsResponsePayload(site, placeable)
  }

  @PutMapping("/{id}/plotSettings")
  @Operation(summary = "Updates plot selection settings without changing existing observations.")
  @ApiResponse200
  @ApiResponse400
  @ApiResponse404
  @ApiResponse409
  fun updatePlotSettings(
      @PathVariable id: PlantingSiteId,
      @RequestBody payload: UpdatePlotSettingsRequestPayload,
  ): PlotSettingsResponsePayload {
    val site = plantingSiteStore.fetchSiteById(id, PlantingSiteDepth.Stratum)
    plantingSiteStore.updatePlotSelectionSettings(
        id,
        payload.defaultPlotPrecisionLevel,
        payload.toEdits(site),
    )
    return getPlotSettings(id)
  }
}

data class ResolvePlotCountRecommendationRequestPayload(
    val stratumId: StratumId,
    @Schema(description = "Observation that recorded the stratum's latest variability snapshot.")
    val observationId: ObservationId,
)

data class UpdatePlotSettingsRequestPayload(
    val defaultPlotPrecisionLevel: PlotPrecisionLevel,
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "Settings to merge into existing strata. Omit to update only the site default.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            )
    )
    val strata: List<UpdateStratumPlotSettingPayload> = emptyList(),
) {
  fun toSettings(
      site: ExistingPlantingSiteModel
  ): Map<StratumId, Map<ObservationType, StratumPlotCounts>> =
      toEdits(site).mapValues { (id, edits) ->
        val stratum = site.strata.first { it.id == id }
        edits.mapValues { (type, edit) -> edit(stratum.plotCounts(type)) }
      }

  fun toEdits(
      site: ExistingPlantingSiteModel
  ): Map<StratumId, Map<ObservationType, (StratumPlotCounts) -> StratumPlotCounts>> {
    val expanded = strata.flatMap { entry ->
      (entry.observationType?.let { listOf(it) } ?: ObservationType.entries).map { type ->
        if (
            entry.observationType == null &&
                entry.mode == PlotSelectionMode.Direct &&
                type == ObservationType.BaselineSurvey
        ) {
          val setting = entry.toSetting() as PlotSelectionSetting.Direct
          entry.copy(
              observationType = type,
              numPermanentPlots = 0,
              numTemporaryPlots = setting.numPermanentPlots + setting.numTemporaryPlots,
          )
        } else {
          entry.copy(observationType = type)
        }
      }
    }
    require(expanded.map { it.stratumId to it.observationType }.distinct().size == expanded.size) {
      "Duplicate stratum and observation type"
    }
    return expanded
        .groupBy { it.stratumId }
        .mapValues { (id, entries) ->
          if (site.strata.none { it.id == id }) throw StratumNotFoundException(id)
          entries.associate { entry ->
            val setting = entry.toSetting()
            val type = entry.observationType!!
            type to
                { current: StratumPlotCounts ->
                  current.applySetting(type, setting)
                }
          }
        }
  }
}

data class UpdateStratumPlotSettingPayload(
    val stratumId: StratumId,
    @Schema(
        description =
            "Observation type to update. Omit or set null to apply to all three types. " +
                "In Direct mode, an all-types entry preserves the supplied counts for Monitoring " +
                "and Biomass Measurements and assigns their sum as temporary plots with zero " +
                "permanent plots for Baseline Survey. Explicit Baseline Survey entries retain " +
                "the zero-permanent and positive-temporary requirements. " +
                "Overlapping entries for the same stratum and type, including an all-types " +
                "entry plus an explicit type, are rejected.",
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
    )
    val observationType: ObservationType? = null,
    @Schema(
        description =
            "Selects required fields: PrecisionLevel requires plotPrecisionLevel; " +
                "Custom requires confidenceLevel, targetMarginOfError, and variability; " +
                "Direct requires numPermanentPlots and numTemporaryPlots. " +
                "Fields for other modes are ignored."
    )
    val mode: PlotSelectionMode,
    @Schema(description = "Required in PrecisionLevel mode; ignored otherwise.")
    val plotPrecisionLevel: PlotPrecisionLevel? = null,
    @Schema(
        description =
            "Required in Custom mode; ignored otherwise. Confidence percentage: 70, 80, 90, 95, or 99.",
        allowableValues = ["70", "80", "90", "95", "99"],
    )
    val confidenceLevel: Int? = null,
    @Schema(
        description =
            "Required in Custom mode; ignored otherwise. Range 5–25, with at most one decimal place.",
        minimum = "5",
        maximum = "25",
        multipleOf = 0.1,
    )
    val targetMarginOfError: BigDecimal? = null,
    @Schema(
        description =
            "Required in Custom mode; ignored otherwise. Range 1–200, with at most one decimal place.",
        minimum = "1",
        maximum = "200",
        multipleOf = 0.1,
    )
    val variability: BigDecimal? = null,
    @Schema(
        description =
            "Required in Direct mode; ignored otherwise. At least one, except BaselineSurvey requires zero.",
        minimum = "0",
        maximum = "10000",
    )
    val numPermanentPlots: Int? = null,
    @Schema(
        description =
            "Required in Direct mode; ignored otherwise. Nonnegative, except BaselineSurvey requires at least one.",
        minimum = "0",
        maximum = "10000",
    )
    val numTemporaryPlots: Int? = null,
) {
  fun toSetting(): PlotSelectionSetting =
      when (mode) {
        PlotSelectionMode.PrecisionLevel ->
            PlotSelectionSetting.PrecisionLevel(
                requireNotNull(plotPrecisionLevel) { "plotPrecisionLevel is required" }
            )
        PlotSelectionMode.Custom ->
            PlotSelectionSetting.Custom(
                requireNotNull(
                    ConfidenceLevel.entries.firstOrNull { it.percent == confidenceLevel }
                ) {
                  "Invalid or missing confidenceLevel"
                },
                requireNotNull(targetMarginOfError) { "targetMarginOfError is required" },
                requireNotNull(variability) { "variability is required" },
            )
        PlotSelectionMode.Direct ->
            PlotSelectionSetting.Direct(
                requireNotNull(numPermanentPlots) { "numPermanentPlots is required" },
                requireNotNull(numTemporaryPlots) { "numTemporaryPlots is required" },
            )
      }
}

data class PlotSettingsResponsePayload(
    val defaultPlotPrecisionLevel: PlotPrecisionLevel,
    val strata: List<StratumPlotSettingsPayload>,
) : SuccessResponsePayload {
  constructor(
      model: ExistingPlantingSiteModel,
      placeableByStratum: Map<StratumId, Map<ObservationType, PlaceablePlotCounts>> = emptyMap(),
  ) : this(
      defaultPlotPrecisionLevel = model.defaultPlotPrecisionLevel,
      strata =
          model.strata.map {
            StratumPlotSettingsPayload(it, placeableByStratum[it.id].orEmpty())
          },
  )
}

data class StratumPlotSettingsPayload(
    val stratumId: StratumId,
    val name: String,
    val settings: List<PlotSelectionSettingPayload>,
) {
  constructor(
      model: ExistingStratumModel,
      placeableByType: Map<ObservationType, PlaceablePlotCounts> = emptyMap(),
  ) : this(
      stratumId = model.id,
      name = model.name,
      settings =
          ObservationType.entries.map { type ->
            PlotSelectionSettingPayload(type, model.plotCounts(type), placeableByType[type])
          },
  )
}

data class PlotSelectionSettingPayload(
    val observationType: ObservationType,
    val mode: PlotSelectionMode,
    @Schema(description = "Set when mode is PrecisionLevel; null when mode is Custom or Direct.")
    val plotPrecisionLevel: PlotPrecisionLevel?,
    @Schema(
        description =
            "Confidence percentage in any mode, or null for a nonstandard stored Student's t."
    )
    val confidenceLevel: Int?,
    val targetMarginOfError: BigDecimal,
    @Schema(description = "Variability percentage rounded to one decimal place.")
    val variability: BigDecimal,
    val numPermanentPlots: Int,
    val numTemporaryPlots: Int,
    @Schema(
        description =
            "Permanent plots that fit in the stratum, or null if the planting site has no map."
    )
    val numPlaceablePermanentPlots: Int?,
    @Schema(
        description =
            "Temporary plots that fit in the stratum, or null if the planting site has no map."
    )
    val numPlaceableTemporaryPlots: Int?,
    @Schema(
        description =
            "Unrounded expected precision percentage in any mode, or null for a nonstandard " +
                "stored Student's t or fewer than two plots."
    )
    val expectedPrecision: Double?,
) {
  constructor(
      type: ObservationType,
      counts: StratumPlotCounts,
      placeable: PlaceablePlotCounts? = null,
  ) : this(
      observationType = type,
      mode = counts.mode,
      plotPrecisionLevel = counts.plotPrecisionLevel,
      confidenceLevel = counts.confidenceLevel?.percent,
      targetMarginOfError = counts.errorMargin,
      variability = counts.variability.setScale(1, RoundingMode.HALF_UP),
      numPermanentPlots = counts.numPermanentPlots,
      numTemporaryPlots = counts.numTemporaryPlots,
      numPlaceablePermanentPlots = placeable?.numPermanentPlots,
      numPlaceableTemporaryPlots = placeable?.numTemporaryPlots,
      expectedPrecision = counts.expectedPrecision,
  )
}
