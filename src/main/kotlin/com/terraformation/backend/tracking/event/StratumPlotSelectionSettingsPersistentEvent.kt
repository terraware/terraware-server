package com.terraformation.backend.tracking.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.eventlog.EntityUpdatedPersistentEvent
import com.terraformation.backend.tracking.model.StratumPlotCounts
import java.math.BigDecimal

sealed interface StratumPlotSelectionSettingsPersistentEvent : EntityUpdatedPersistentEvent {
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
  val stratumId: StratumId
  val stratumName: String
}

data class StratumPlotSelectionSettingsChangedEventV1(
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    override val stratumId: StratumId,
    override val stratumName: String,
    val source: Source,
    val settings: List<Settings>,
    val observationId: ObservationId? = null,
    val measuredVariability: BigDecimal? = null,
) : StratumPlotSelectionSettingsPersistentEvent {
  enum class Source {
    UserEdit,
    AcceptedRecommendation,
    Terraformation,
  }

  data class Settings(
      val observationType: ObservationType,
      val mode: PlotSelectionMode,
      val plotPrecisionLevel: PlotPrecisionLevel?,
      val variance: BigDecimal,
      val studentsT: BigDecimal,
      val errorMargin: BigDecimal,
      val numPermanentPlots: Int,
      val numTemporaryPlots: Int,
  ) {
    companion object {
      fun of(type: ObservationType, counts: StratumPlotCounts) =
          Settings(
              observationType = type,
              mode = counts.mode,
              plotPrecisionLevel = counts.plotPrecisionLevel,
              variance = counts.variance,
              studentsT = counts.studentsT,
              errorMargin = counts.errorMargin,
              numPermanentPlots = counts.numPermanentPlots,
              numTemporaryPlots = counts.numTemporaryPlots,
          )
    }
  }
}

typealias StratumPlotSelectionSettingsChangedEvent = StratumPlotSelectionSettingsChangedEventV1

typealias StratumPlotSelectionSettingsChangedEventSource =
    StratumPlotSelectionSettingsChangedEventV1.Source

typealias StratumPlotSelectionSettingsChangedEventSettings =
    StratumPlotSelectionSettingsChangedEventV1.Settings
