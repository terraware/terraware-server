package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingStratumModel
import com.terraformation.backend.tracking.model.PlotSelectionSetting
import com.terraformation.backend.tracking.model.StratumPlotCounts

data class PlotSettingsPreviewResponsePayload(val strata: List<StratumPlotSettingsPreviewPayload>) :
    SuccessResponsePayload {
  constructor(
      site: ExistingPlantingSiteModel,
      settings: Map<StratumId, Map<ObservationType, StratumPlotCounts>>,
  ) : this(site.strata.map { StratumPlotSettingsPreviewPayload(it, settings[it.id].orEmpty()) })
}

data class StratumPlotSettingsPreviewPayload(
    val stratumId: StratumId,
    val name: String,
    val settings: List<PlotSettingPreviewPayload>,
) {
  constructor(
      model: ExistingStratumModel,
      proposed: Map<ObservationType, StratumPlotCounts>,
  ) : this(
      stratumId = model.id,
      name = model.name,
      settings =
          ObservationType.entries
              .sortedBy { it.id }
              .map {
                val current = model.plotCounts(it)
                PlotSettingPreviewPayload(it, current, proposed[it] ?: current)
              },
  )
}

data class PlotSettingPreviewPayload(
    val observationType: ObservationType,
    val current: PlotCountsPreviewPayload,
    val proposed: PlotCountsPreviewPayload,
    val precisionLevels: List<PrecisionLevelPlotCountsPreviewPayload>,
    val custom: PlotCountsPreviewPayload?,
) {
  constructor(
      type: ObservationType,
      current: StratumPlotCounts,
      proposed: StratumPlotCounts,
  ) : this(
      observationType = type,
      current = PlotCountsPreviewPayload(current),
      proposed = PlotCountsPreviewPayload(proposed),
      precisionLevels =
          PlotPrecisionLevel.entries
              .sortedBy { it.id }
              .map {
                PrecisionLevelPlotCountsPreviewPayload(
                    it,
                    PlotCountsPreviewPayload(
                        proposed.applySetting(type, PlotSelectionSetting.PrecisionLevel(it))
                    ),
                )
              },
      custom =
          proposed.confidenceLevel?.let {
            PlotCountsPreviewPayload(proposed.recalculatePlotCounts(type))
          },
  )
}

data class PrecisionLevelPlotCountsPreviewPayload(
    val plotPrecisionLevel: PlotPrecisionLevel,
    val counts: PlotCountsPreviewPayload,
)

data class PlotCountsPreviewPayload(
    val numPermanentPlots: Int,
    val numTemporaryPlots: Int,
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
