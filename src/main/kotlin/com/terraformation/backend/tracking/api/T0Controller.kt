package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.tracking.db.T0PlotStore
import io.swagger.v3.oas.annotations.Operation
import java.math.BigDecimal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/t0")
@RestController
@TrackingEndpoint
class T0Controller(private val t0PlotStore: T0PlotStore) {
  @Operation(summary = "Assigns an observation as T0 for a monitoring plot.")
  @PostMapping("/{monitoringPlotId}/observation/{observationId}")
  fun assignT0PlotObservation(
      @PathVariable monitoringPlotId: MonitoringPlotId,
      @PathVariable observationId: ObservationId,
  ): SimpleSuccessResponsePayload {
    t0PlotStore.assignT0PlotObservation(monitoringPlotId, observationId)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Assigns a species and estimated density as T0 for a monitoring plot.")
  @PostMapping("/{monitoringPlotId}/species")
  fun assignT0PlotSpeciesDensity(
      @PathVariable monitoringPlotId: MonitoringPlotId,
      @RequestBody payload: AssignT0PlotSpeciesPayload,
  ): SimpleSuccessResponsePayload {
    t0PlotStore.assignT0PlotSpeciesDensity(monitoringPlotId, payload.speciesId, payload.density)

    return SimpleSuccessResponsePayload()
  }
}

data class AssignT0PlotSpeciesPayload(val speciesId: SpeciesId, val density: BigDecimal)
