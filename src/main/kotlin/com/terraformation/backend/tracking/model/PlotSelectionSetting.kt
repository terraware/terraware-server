package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import java.math.BigDecimal

sealed interface PlotSelectionSetting {
  data class PrecisionLevel(val level: PlotPrecisionLevel) : PlotSelectionSetting

  data class Custom(
      val confidenceLevel: ConfidenceLevel,
      val targetMarginOfError: BigDecimal,
      val variability: BigDecimal,
  ) : PlotSelectionSetting

  data class Direct(val numPermanentPlots: Int, val numTemporaryPlots: Int) : PlotSelectionSetting
}
