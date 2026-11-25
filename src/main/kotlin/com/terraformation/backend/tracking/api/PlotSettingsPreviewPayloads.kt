package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingStratumModel
import com.terraformation.backend.tracking.model.PlaceablePlotCounts
import com.terraformation.backend.tracking.model.PlotSelectionSetting
import com.terraformation.backend.tracking.model.PreviewPlaceablePlotCounts
import com.terraformation.backend.tracking.model.StratumPlotCounts
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema

data class PlotSettingsPreviewResponsePayload(val strata: List<StratumPlotSettingsPreviewPayload>) :
    SuccessResponsePayload {
  constructor(
      site: ExistingPlantingSiteModel,
      settings: Map<StratumId, Map<ObservationType, StratumPlotCounts>>,
      placeableByStratum: Map<StratumId, Map<ObservationType, PreviewPlaceablePlotCounts>> =
          emptyMap(),
  ) : this(
      site.strata.map {
        StratumPlotSettingsPreviewPayload(
            it,
            settings[it.id].orEmpty(),
            placeableByStratum[it.id].orEmpty(),
        )
      }
  )
}

data class StratumPlotSettingsPreviewPayload(
    val stratumId: StratumId,
    val name: String,
    val settings: List<PlotSettingPreviewPayload>,
) {
  constructor(
      model: ExistingStratumModel,
      proposed: Map<ObservationType, StratumPlotCounts>,
      placeableByType: Map<ObservationType, PreviewPlaceablePlotCounts> = emptyMap(),
  ) : this(
      stratumId = model.id,
      name = model.name,
      settings =
          ObservationType.entries.map { type ->
            val currentCounts = model.plotCounts(type)
            val placeable = placeableByType[type]
            PlotSettingPreviewPayload(
                type,
                currentCounts,
                proposed[type] ?: currentCounts,
                placeable?.current,
                placeable?.proposed,
            )
          },
  )
}

data class PlotSettingPreviewPayload(
    val observationType: ObservationType,
    val current: PlaceablePlotCountsPreviewPayload,
    val proposed: PlaceablePlotCountsPreviewPayload,
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "Alternatives for every precision level. Counts and expected precision are null " +
                        "when an alternative exceeds the supported plot count limit."
            )
    )
    val precisionLevels: List<PrecisionLevelPlotCountsPreviewPayload>,
    @Schema(
        description =
            "Custom alternative using the proposed parameters, or null for nonstandard confidence. " +
                "Counts and expected precision are null when the alternative exceeds the " +
                "supported plot count limit. Requested settings must still satisfy the limit."
    )
    val custom: PlotCountsPreviewPayload?,
) {
  constructor(
      type: ObservationType,
      current: StratumPlotCounts,
      proposed: StratumPlotCounts,
      currentPlaceable: PlaceablePlotCounts? = null,
      proposedPlaceable: PlaceablePlotCounts? = null,
  ) : this(
      observationType = type,
      current = PlaceablePlotCountsPreviewPayload(current, currentPlaceable),
      proposed = PlaceablePlotCountsPreviewPayload(proposed, proposedPlaceable),
      precisionLevels =
          PlotPrecisionLevel.entries
              .sortedBy { it.id }
              .map {
                PrecisionLevelPlotCountsPreviewPayload(
                    it,
                    previewAlternative {
                      proposed.applySetting(type, PlotSelectionSetting.PrecisionLevel(it))
                    },
                )
              },
      custom =
          proposed.confidenceLevel?.let {
            previewAlternative { proposed.recalculatePlotCounts(type) }
          },
  )
}

data class PrecisionLevelPlotCountsPreviewPayload(
    val plotPrecisionLevel: PlotPrecisionLevel,
    val counts: PlotCountsPreviewPayload,
)

data class PlaceablePlotCountsPreviewPayload(
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
    val expectedPrecision: Double?,
) {
  constructor(
      counts: StratumPlotCounts,
      placeable: PlaceablePlotCounts? = null,
  ) : this(
      numPermanentPlots = counts.numPermanentPlots,
      numTemporaryPlots = counts.numTemporaryPlots,
      numPlaceablePermanentPlots = placeable?.numPermanentPlots,
      numPlaceableTemporaryPlots = placeable?.numTemporaryPlots,
      expectedPrecision = counts.expectedPrecision,
  )
}

data class PlotCountsPreviewPayload(
    val numPermanentPlots: Int?,
    val numTemporaryPlots: Int?,
    val expectedPrecision: Double?,
) {
  constructor(
      counts: StratumPlotCounts
  ) : this(
      numPermanentPlots = counts.numPermanentPlots,
      numTemporaryPlots = counts.numTemporaryPlots,
      expectedPrecision = counts.expectedPrecision,
  )
}

private fun previewAlternative(calculate: () -> StratumPlotCounts): PlotCountsPreviewPayload {
  val counts =
      try {
        calculate()
      } catch (_: IllegalArgumentException) {
        return PlotCountsPreviewPayload(null, null, null)
      } catch (_: ArithmeticException) {
        return PlotCountsPreviewPayload(null, null, null)
      }
  return if (counts.numPermanentPlots > 10000 || counts.numTemporaryPlots > 10000) {
    PlotCountsPreviewPayload(null, null, null)
  } else {
    PlotCountsPreviewPayload(counts)
  }
}
