package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonAlias
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.tracking.T0Service
import com.terraformation.backend.tracking.db.T0Store
import com.terraformation.backend.tracking.model.OptionalSpeciesDensityModel
import com.terraformation.backend.tracking.model.PlotSpeciesModel
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
    private val t0Service: T0Service,
    private val t0Store: T0Store,
) {
  @Operation(summary = "Get all saved T0 Data for a planting site")
  @GetMapping("/site/{plantingSiteId}")
  fun getT0SiteData(@PathVariable plantingSiteId: PlantingSiteId): GetSiteT0DataResponsePayload {
    val siteData = t0Store.fetchT0SiteData(plantingSiteId)

    return GetSiteT0DataResponsePayload(data = SiteT0DataResponsePayload(siteData))
  }

  @Operation(summary = "Get whether or not all T0 Data has been set for a planting site")
  @GetMapping("/site/{plantingSiteId}/allSet")
  fun getAllT0SiteDataSet(
      @PathVariable plantingSiteId: PlantingSiteId
  ): GetAllSiteT0DataSetResponsePayload {
    val allSet = t0Store.fetchAllT0SiteDataSet(plantingSiteId)

    return GetAllSiteT0DataSetResponsePayload(allSet = allSet)
  }

  @GetMapping("/site/{plantingSiteId}/species")
  @Operation(
      summary =
          "Lists all the species that have been withdrawn to a planting site or recorded in " +
              "observations (if not withdrawn).",
      description =
          "Species with densities are species that were withdrawn, species with null densities are " +
              "species that were recorded in observations but not withdrawn to the plot's subzone.",
  )
  fun getT0SpeciesForPlantingSite(
      @PathVariable("plantingSiteId") plantingSiteId: PlantingSiteId
  ): GetSitePlotSpeciesResponsePayload {
    val plots = t0Store.fetchSiteSpeciesByPlot(plantingSiteId)

    return GetSitePlotSpeciesResponsePayload(plots.map { PlotSpeciesDensitiesPayload(it) })
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
    t0Service.assignT0PlotsData(payload.plots.map { it.toModel() })

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Assign T0 Data for a planting site, only applicable to temporary plots")
  @PostMapping("/site/temp")
  fun assignT0TempSiteData(
      @RequestBody payload: AssignSiteT0TempDataRequestPayload
  ): SimpleSuccessResponsePayload {
    t0Service.assignT0TempZoneData(payload.zones.map { it.toModel() })

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

data class ZoneT0DataPayload(
    val plantingZoneId: StratumId,
    val densityData: List<SpeciesDensityPayload> = emptyList(),
) {
  constructor(
      model: ZoneT0TempDataModel
  ) : this(
      plantingZoneId = model.plantingZoneId,
      densityData = model.densityData.map { SpeciesDensityPayload(it) },
  )

  fun toModel() =
      ZoneT0TempDataModel(
          plantingZoneId = plantingZoneId,
          densityData = densityData.map { it.toModel() },
      )
}

data class SiteT0DataResponsePayload(
    val plantingSiteId: PlantingSiteId,
    val survivalRateIncludesTempPlots: Boolean = false,
    val plots: List<PlotT0DataPayload> = emptyList(),
    val zones: List<ZoneT0DataPayload> = emptyList(),
) {
  constructor(
      model: SiteT0DataModel
  ) : this(
      plantingSiteId = model.plantingSiteId,
      survivalRateIncludesTempPlots = model.survivalRateIncludesTempPlots,
      plots = model.plots.map { PlotT0DataPayload(it) },
      zones = model.zones.map { ZoneT0DataPayload(it) },
  )
}

data class OptionalSpeciesDensityPayload(
    val speciesId: SpeciesId,
    val density: BigDecimal?,
) {
  constructor(
      model: OptionalSpeciesDensityModel
  ) : this(
      speciesId = model.speciesId,
      density = model.density,
  )
}

data class PlotSpeciesDensitiesPayload(
    val monitoringPlotId: MonitoringPlotId,
    val species: List<OptionalSpeciesDensityPayload>,
) {
  constructor(
      model: PlotSpeciesModel
  ) : this(
      monitoringPlotId = model.monitoringPlotId,
      species = model.species.map { OptionalSpeciesDensityPayload(it) },
  )
}

data class GetSitePlotSpeciesResponsePayload(val plots: List<PlotSpeciesDensitiesPayload>) :
    SuccessResponsePayload

data class GetSiteT0DataResponsePayload(val data: SiteT0DataResponsePayload) :
    SuccessResponsePayload

data class GetAllSiteT0DataSetResponsePayload(val allSet: Boolean) : SuccessResponsePayload

data class AssignSiteT0DataRequestPayload(
    val plantingSiteId: PlantingSiteId,
    val plots: List<PlotT0DataPayload> = emptyList(),
)

data class AssignSiteT0TempDataRequestPayload(
    val plantingSiteId: PlantingSiteId,
    val zones: List<ZoneT0DataPayload> = emptyList(),
)
