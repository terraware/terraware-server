package com.terraformation.backend.tracking.edit

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import java.math.BigDecimal

/** One item in a summary of the changes a [PlantingSiteEdit] would make. */
sealed interface PlantingSiteEditSummaryItem {
  /** The shallowest depth at which this item is included in a summary. */
  val depth: PlantingSiteDepth

  /** In-progress observations that would be abandoned because their strata are being edited. */
  data class ObservationsAbandoned(val observationIds: List<ObservationId>) :
      PlantingSiteEditSummaryItem {
    override val depth: PlantingSiteDepth
      get() = PlantingSiteDepth.Site
  }

  /** Net change in the site's plantable area. */
  data class SiteAreaChanged(val areaHaDifference: BigDecimal) : PlantingSiteEditSummaryItem {
    override val depth: PlantingSiteDepth
      get() = PlantingSiteDepth.Site
  }

  data class StratumCreated(
      val stratumName: String,
      val stableId: StableId,
      val numPermanentPlots: Int,
  ) : PlantingSiteEditSummaryItem {
    override val depth: PlantingSiteDepth
      get() = PlantingSiteDepth.Stratum
  }

  data class StratumDeleted(
      val stratumName: String,
      val stableId: StableId,
  ) : PlantingSiteEditSummaryItem {
    override val depth: PlantingSiteDepth
      get() = PlantingSiteDepth.Stratum
  }

  data class StratumUpdated(
      val stratumName: String,
      val areaHaDifference: BigDecimal,
      /** Percentage of the stratum's new boundary that overlaps with its existing boundary. */
      val overlapPercent: Double,
      val numPermanentPlots: Int,
  ) : PlantingSiteEditSummaryItem {
    override val depth: PlantingSiteDepth
      get() = PlantingSiteDepth.Stratum
  }

  data class SubstratumCreated(
      val stratumName: String,
      val substratumName: String,
      val stableId: StableId,
  ) : PlantingSiteEditSummaryItem {
    override val depth: PlantingSiteDepth
      get() = PlantingSiteDepth.Substratum
  }

  data class SubstratumDeleted(
      val stratumName: String,
      val substratumName: String,
      val stableId: StableId,
  ) : PlantingSiteEditSummaryItem {
    override val depth: PlantingSiteDepth
      get() = PlantingSiteDepth.Substratum
  }

  data class SubstratumUpdated(
      val stratumName: String,
      val substratumName: String,
      val areaHaDifference: BigDecimal,
      /** Percentage of the substratum's new boundary that overlaps with its existing boundary. */
      val overlapPercent: Double,
  ) : PlantingSiteEditSummaryItem {
    override val depth: PlantingSiteDepth
      get() = PlantingSiteDepth.Substratum
  }

  /**
   * A new permanent monitoring plot would be created. New plots are normally created at the stratum
   * level, in which case [substratumName] is null.
   */
  data class PermanentPlotCreated(
      val stratumName: String,
      val substratumName: String?,
      val permanentIndex: Int?,
      /** True if the plot would be in an area that was already part of the stratum. */
      val inExistingArea: Boolean,
  ) : PlantingSiteEditSummaryItem {
    override val depth: PlantingSiteDepth
      get() = PlantingSiteDepth.Plot
  }

  /** An existing plot would move into this substratum and/or get a new permanent index. */
  data class PlotAdopted(
      val stratumName: String,
      val substratumName: String,
      val monitoringPlotId: MonitoringPlotId,
      val permanentIndex: Int?,
  ) : PlantingSiteEditSummaryItem {
    override val depth: PlantingSiteDepth
      get() = PlantingSiteDepth.Plot
  }

  /** An existing plot would no longer be in any substratum. */
  data class PlotEjected(
      val stratumName: String,
      val substratumName: String,
      val monitoringPlotId: MonitoringPlotId,
  ) : PlantingSiteEditSummaryItem {
    override val depth: PlantingSiteDepth
      get() = PlantingSiteDepth.Plot
  }

  /** An existing plot would stay where it is with the same permanent index. */
  data class PlotAccepted(
      val stratumName: String,
      val substratumName: String,
      val monitoringPlotId: MonitoringPlotId,
      val permanentIndex: Int?,
  ) : PlantingSiteEditSummaryItem {
    override val depth: PlantingSiteDepth
      get() = PlantingSiteDepth.Plot
  }
}
