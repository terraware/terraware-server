package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.tracking.T0PlotService
import com.terraformation.backend.tracking.db.T0PlotStore
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.SpeciesDensityModel
import io.swagger.v3.oas.annotations.Operation
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
class T0Controller(
    private val t0PlotService: T0PlotService,
    private val t0PlotStore: T0PlotStore,
) {
  @Operation(summary = "Get all saved T0 Data for a planting site")
  @GetMapping("/site/{plantingSiteId}")
  fun getT0SiteData(@PathVariable plantingSiteId: PlantingSiteId): SiteT0DataPayload {
    val plotData = t0PlotStore.fetchT0SiteData(plantingSiteId)

    return SiteT0DataPayload(plantingSiteId, plotData.map { PlotT0DataPayload(it) })
  }

  @Operation
  @PostMapping("/site")
  fun assignT0SiteData(
      @RequestBody payload: SiteT0DataPayload,
  ): SimpleSuccessResponsePayload {
    t0PlotService.assignT0PlotsData(payload.plots.map { it.toModel() })

    return SimpleSuccessResponsePayload()
  }
}

data class SpeciesDensityPayload(
    val speciesId: SpeciesId,
    val plotDensity: BigDecimal,
) {
  constructor(
      model: SpeciesDensityModel
  ) : this(
      speciesId = model.speciesId,
      plotDensity = model.plotDensity,
  )

  fun toModel() = SpeciesDensityModel(speciesId = speciesId, plotDensity = plotDensity)
}

data class PlotT0DataPayload(
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId? = null,
    val densityData: List<SpeciesDensityPayload>,
) {
  constructor(
      model: PlotT0DataModel,
      observationId: ObservationId? = null,
  ) : this(
      monitoringPlotId = model.monitoringPlotId,
      observationId = observationId,
      densityData = model.densityData.map { SpeciesDensityPayload(it) },
  )

  fun toModel() =
      PlotT0DataModel(
          monitoringPlotId = monitoringPlotId,
          observationId = observationId,
          densityData = densityData.map { it.toModel() },
      )
}

data class SiteT0DataPayload(
    val plantingSiteId: PlantingSiteId,
    val plots: List<PlotT0DataPayload> = emptyList(),
) : SuccessResponsePayload
