package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.tracking.db.T0PlotStore
import com.terraformation.backend.tracking.model.PlotT0DataModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/t0")
@RestController
@TrackingEndpoint
class T0Controller(private val t0PlotStore: T0PlotStore) {
  @Operation(summary = "Get all saved T0 Data for a planting site")
  @GetMapping("/site/{plantingSiteId}")
  fun getT0SiteData(@PathVariable plantingSiteId: PlantingSiteId): SiteT0DataPayload {
    val plotData = t0PlotStore.fetchT0SiteData(plantingSiteId)

    return SiteT0DataPayload(plantingSiteId, plotData.map { PlotT0DataPayload(it) })
  }

  @Operation(summary = "Assigns an observation as T0 for a monitoring plot.")
  @PostMapping("/plot/{monitoringPlotId}/observation/{observationId}")
  fun assignT0PlotObservation(
      @PathVariable monitoringPlotId: MonitoringPlotId,
      @PathVariable observationId: ObservationId,
  ): SimpleSuccessResponsePayload {
    t0PlotStore.assignT0PlotObservation(monitoringPlotId, observationId)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Assigns a species and plot density as T0 for a monitoring plot.")
  @PostMapping("/plot/{monitoringPlotId}/species")
  fun assignT0PlotSpeciesDensity(
      @PathVariable monitoringPlotId: MonitoringPlotId,
      @RequestBody payload: AssignT0PlotSpeciesPayload,
  ): SimpleSuccessResponsePayload {
    t0PlotStore.assignT0PlotSpeciesDensity(monitoringPlotId, payload.speciesId, payload.plotDensity)

    return SimpleSuccessResponsePayload()
  }
}

data class PlotT0DataPayload(
    val monitoringPlotId: MonitoringPlotId,
    val speciesId: SpeciesId,
    val plotDensity: BigDecimal,
    val observationId: ObservationId? = null,
) {
  constructor(
      model: PlotT0DataModel
  ) : this(
      monitoringPlotId = model.monitoringPlotId,
      speciesId = model.speciesId,
      plotDensity = model.plotDensity,
      observationId = model.observationId,
  )
}

data class SiteT0DataPayload(
    val plantingSiteId: PlantingSiteId,
    val plots: List<PlotT0DataPayload> = emptyList(),
) : SuccessResponsePayload

data class AssignT0PlotSpeciesPayload(
    val speciesId: SpeciesId,
    @Schema(description = "Plants per plot") val plotDensity: BigDecimal,
)
