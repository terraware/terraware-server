package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.tracking.edit.PlantingSiteEditSummaryItem
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@Schema(
    description =
        "One change that a planting site map edit would make. The type property determines " +
            "which other properties are present."
)
sealed interface PlantingSiteEditSummaryItemPayload {
  companion object {
    fun of(model: PlantingSiteEditSummaryItem): PlantingSiteEditSummaryItemPayload {
      return when (model) {
        is PlantingSiteEditSummaryItem.ObservationsAbandoned ->
            ObservationsAbandonedPayload(model.observationIds)
        is PlantingSiteEditSummaryItem.SiteAreaChanged ->
            SiteAreaChangedPayload(model.areaHaDifference)
        is PlantingSiteEditSummaryItem.StratumCreated ->
            StratumCreatedPayload(model.stratumName, model.stableId, model.numPermanentPlots)
        is PlantingSiteEditSummaryItem.StratumDeleted ->
            StratumDeletedPayload(model.stratumName, model.stableId)
        is PlantingSiteEditSummaryItem.StratumUpdated ->
            StratumUpdatedPayload(
                model.stratumName,
                model.areaHaDifference,
                model.overlapPercent,
                model.numPermanentPlots,
            )
        is PlantingSiteEditSummaryItem.SubstratumCreated ->
            SubstratumCreatedPayload(model.stratumName, model.substratumName, model.stableId)
        is PlantingSiteEditSummaryItem.SubstratumDeleted ->
            SubstratumDeletedPayload(model.stratumName, model.substratumName, model.stableId)
        is PlantingSiteEditSummaryItem.SubstratumUpdated ->
            SubstratumUpdatedPayload(
                model.stratumName,
                model.substratumName,
                model.areaHaDifference,
                model.overlapPercent,
            )
        is PlantingSiteEditSummaryItem.PermanentPlotCreated ->
            PermanentPlotCreatedPayload(
                model.stratumName,
                model.substratumName,
                model.permanentIndex,
                model.inExistingArea,
            )
        is PlantingSiteEditSummaryItem.PlotAdopted ->
            PlotAdoptedPayload(
                model.stratumName,
                model.substratumName,
                model.monitoringPlotId,
                model.permanentIndex,
            )
        is PlantingSiteEditSummaryItem.PlotEjected ->
            PlotEjectedPayload(model.stratumName, model.substratumName, model.monitoringPlotId)
        is PlantingSiteEditSummaryItem.PlotAccepted ->
            PlotAcceptedPayload(
                model.stratumName,
                model.substratumName,
                model.monitoringPlotId,
                model.permanentIndex,
            )
      }
    }
  }
}

@JsonTypeName("ObservationsAbandoned")
@Schema(
    description =
        "In-progress observations would be abandoned because they have incomplete plots in " +
            "edited strata."
)
data class ObservationsAbandonedPayload(val observationIds: List<ObservationId>) :
    PlantingSiteEditSummaryItemPayload

@JsonTypeName("SiteAreaChanged")
@Schema(description = "Net change in the site's plantable area.")
data class SiteAreaChangedPayload(val areaHaDifference: BigDecimal) :
    PlantingSiteEditSummaryItemPayload

@JsonTypeName("StratumCreated")
data class StratumCreatedPayload(
    val stratumName: String,
    val stableId: StableId,
    val numPermanentPlots: Int,
) : PlantingSiteEditSummaryItemPayload

@JsonTypeName("StratumDeleted")
data class StratumDeletedPayload(
    val stratumName: String,
    val stableId: StableId,
) : PlantingSiteEditSummaryItemPayload

@JsonTypeName("StratumUpdated")
data class StratumUpdatedPayload(
    val stratumName: String,
    val areaHaDifference: BigDecimal,
    @Schema(
        description =
            "Percentage of the stratum's new boundary that overlaps with its existing boundary."
    )
    val overlapPercent: Double,
    val numPermanentPlots: Int,
) : PlantingSiteEditSummaryItemPayload

@JsonTypeName("SubstratumCreated")
data class SubstratumCreatedPayload(
    val stratumName: String,
    val substratumName: String,
    val stableId: StableId,
) : PlantingSiteEditSummaryItemPayload

@JsonTypeName("SubstratumDeleted")
data class SubstratumDeletedPayload(
    val stratumName: String,
    val substratumName: String,
    val stableId: StableId,
) : PlantingSiteEditSummaryItemPayload

@JsonTypeName("SubstratumUpdated")
data class SubstratumUpdatedPayload(
    val stratumName: String,
    val substratumName: String,
    val areaHaDifference: BigDecimal,
    @Schema(
        description =
            "Percentage of the substratum's new boundary that overlaps with its existing boundary."
    )
    val overlapPercent: Double,
) : PlantingSiteEditSummaryItemPayload

@JsonTypeName("PermanentPlotCreated")
data class PermanentPlotCreatedPayload(
    val stratumName: String,
    @Schema(description = "Normally null; new plots are created at the stratum level.")
    val substratumName: String?,
    val permanentIndex: Int?,
    @Schema(
        description = "True if the plot would be in an area that was already part of the stratum."
    )
    val inExistingArea: Boolean,
) : PlantingSiteEditSummaryItemPayload

@JsonTypeName("PlotAdopted")
@Schema(
    description =
        "An existing monitoring plot would move into this substratum and/or get a new " +
            "permanent index."
)
data class PlotAdoptedPayload(
    val stratumName: String,
    val substratumName: String,
    val monitoringPlotId: MonitoringPlotId,
    val permanentIndex: Int?,
) : PlantingSiteEditSummaryItemPayload

@JsonTypeName("PlotEjected")
@Schema(description = "An existing monitoring plot would no longer be in any substratum.")
data class PlotEjectedPayload(
    val stratumName: String,
    val substratumName: String,
    val monitoringPlotId: MonitoringPlotId,
) : PlantingSiteEditSummaryItemPayload

@JsonTypeName("PlotAccepted")
@Schema(
    description =
        "An existing monitoring plot would stay in its substratum with the same permanent index."
)
data class PlotAcceptedPayload(
    val stratumName: String,
    val substratumName: String,
    val monitoringPlotId: MonitoringPlotId,
    val permanentIndex: Int?,
) : PlantingSiteEditSummaryItemPayload
