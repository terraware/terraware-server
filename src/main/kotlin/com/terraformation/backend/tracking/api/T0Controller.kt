package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonAlias
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.tracking.T0PlotService
import com.terraformation.backend.tracking.db.T0PlotStore
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.SiteT0DataModel
import com.terraformation.backend.tracking.model.SpeciesDensityModel
import com.terraformation.backend.tracking.model.ZoneT0TempDataModel
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
  fun getT0SiteData(@PathVariable plantingSiteId: PlantingSiteId): GetSiteT0DataResponsePayload {
    val siteData = t0PlotStore.fetchT0SiteData(plantingSiteId)

    return GetSiteT0DataResponsePayload(data = SiteT0DataResponsePayload(siteData))
  }

  @Operation(
      summary = "Assign T0 Data for a planting site",
      description =
          "Deletes existing densities in the same plot if they don't appear in the payload.",
  )
  @PostMapping("/site")
  fun assignT0SiteData(
      @RequestBody payload: AssignSiteT0DataRequestPayload,
  ): SimpleSuccessResponsePayload {
    t0PlotService.assignT0PlotsData(payload.plots.map { it.toModel() })

    return SimpleSuccessResponsePayload()
  }
}

data class SpeciesDensityPayload(
    val speciesId: SpeciesId,
    @JsonAlias("plotDensity") val density: BigDecimal,
) {
  constructor(
      model: SpeciesDensityModel
  ) : this(
      speciesId = model.speciesId,
      density = model.density,
  )

  val plotDensity: BigDecimal // for backwards compatibility in response payloads
    get() = density

  fun toModel() = SpeciesDensityModel(speciesId = speciesId, density = density)
}

data class PlotT0DataPayload(
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId? = null,
    val densityData: List<SpeciesDensityPayload>,
) {
  constructor(
      model: PlotT0DataModel
  ) : this(
      monitoringPlotId = model.monitoringPlotId,
      observationId = model.observationId,
      densityData = model.densityData.map { SpeciesDensityPayload(it) },
  )

  fun toModel() =
      PlotT0DataModel(
          monitoringPlotId = monitoringPlotId,
          observationId = observationId,
          densityData = densityData.map { it.toModel() },
      )
}

data class AssignSiteT0DataRequestPayload(
    val plantingSiteId: PlantingSiteId,
    val plots: List<PlotT0DataPayload> = emptyList(),
)

data class ZoneT0DataPayload(
    val plantingZoneId: PlantingZoneId,
    val densityData: List<SpeciesDensityPayload> = emptyList(),
) {
  constructor(
      model: ZoneT0TempDataModel
  ) : this(
      plantingZoneId = model.plantingZoneId,
      densityData = model.densityData.map { SpeciesDensityPayload(it) },
  )
}

data class SiteT0DataResponsePayload(
    val plantingSiteId: PlantingSiteId,
    val survivalRatesIncludeTempPlots: Boolean = false,
    val plots: List<PlotT0DataPayload> = emptyList(),
    val zones: List<ZoneT0DataPayload> = emptyList(),
) {
  constructor(
      model: SiteT0DataModel
  ) : this(
      plantingSiteId = model.plantingSiteId,
      survivalRatesIncludeTempPlots = model.survivalRateIncludesTempPlots,
      plots = model.plots.map { PlotT0DataPayload(it) },
      zones = model.zones.map { ZoneT0DataPayload(it) },
  )
}

data class GetSiteT0DataResponsePayload(val data: SiteT0DataResponsePayload) :
    SuccessResponsePayload
