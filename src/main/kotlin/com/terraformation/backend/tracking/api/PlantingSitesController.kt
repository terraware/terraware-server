package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.tracking.PlantingSiteService
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.ExistingPlantingSeasonModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingStratumModel
import com.terraformation.backend.tracking.model.ExistingSubstratumModel
import com.terraformation.backend.tracking.model.MonitoringPlotHistoryModel
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.NewPlantingSiteModel
import com.terraformation.backend.tracking.model.NewStratumModel
import com.terraformation.backend.tracking.model.NewSubstratumModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteHistoryModel
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.PlantingSiteValidationFailure
import com.terraformation.backend.tracking.model.PlantingSiteValidationFailureType
import com.terraformation.backend.tracking.model.StratumHistoryModel
import com.terraformation.backend.tracking.model.StratumModel
import com.terraformation.backend.tracking.model.SubstratumHistoryModel
import com.terraformation.backend.tracking.model.SubstratumModel
import com.terraformation.backend.tracking.model.UpdatedPlantingSeasonModel
import com.terraformation.backend.util.toMultiPolygon
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.ws.rs.BadRequestException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/sites")
@RestController
@TrackingEndpoint
class PlantingSitesController(
    private val plantingSiteStore: PlantingSiteStore,
    private val plantingSiteService: PlantingSiteService,
) {
  @GetMapping
  @Operation(
      summary = "Gets a list of an organization's planting sites.",
      description = "The list can optionally contain information about strata and substrata.",
  )
  fun listPlantingSites(
      @RequestParam //
      organizationId: OrganizationId? = null,
      @RequestParam //
      projectId: ProjectId? = null,
      @RequestParam
      @Schema(
          description = "If true, include strata and substrata for each site.",
          defaultValue = "false",
      )
      full: Boolean?,
  ): ListPlantingSitesResponsePayload {
    val depth = if (full == true) PlantingSiteDepth.Plot else PlantingSiteDepth.Site
    val models =
        if (projectId != null) {
          plantingSiteStore.fetchSitesByProjectId(projectId, depth)
        } else if (organizationId != null) {
          plantingSiteStore.fetchSitesByOrganizationId(organizationId, depth)
        } else {
          throw BadRequestException("One of organizationId or projectId must be specified")
        }
    val payloads = models.map { PlantingSitePayload(it) }
    return ListPlantingSitesResponsePayload(payloads)
  }

  @GetMapping("/{id}")
  @Operation(
      summary = "Gets information about a specific planting site.",
      description = "Includes information about the site's strata and substrata.",
  )
  fun getPlantingSite(
      @PathVariable("id") id: PlantingSiteId,
  ): GetPlantingSiteResponsePayload {
    val model = plantingSiteStore.fetchSiteById(id, PlantingSiteDepth.Plot)
    return GetPlantingSiteResponsePayload(PlantingSitePayload(model))
  }

  @GetMapping("/{id}/history")
  @Operation(summary = "Lists all older versions of a planting site.")
  fun listPlantingSiteHistories(
      @PathVariable("id") id: PlantingSiteId,
  ): ListPlantingSiteHistoriesResponsePayload {
    val models = plantingSiteStore.fetchSiteHistories(id, PlantingSiteDepth.Plot)
    return ListPlantingSiteHistoriesResponsePayload(models.map { PlantingSiteHistoryPayload(it) })
  }

  @GetMapping("/{id}/history/{historyId}")
  @Operation(summary = "Gets information about an older version of a planting site.")
  fun getPlantingSiteHistory(
      @PathVariable("id") id: PlantingSiteId,
      @PathVariable("historyId") historyId: PlantingSiteHistoryId,
  ): GetPlantingSiteHistoryResponsePayload {
    val model = plantingSiteStore.fetchSiteHistoryById(id, historyId, PlantingSiteDepth.Plot)
    return GetPlantingSiteHistoryResponsePayload(PlantingSiteHistoryPayload(model))
  }

  @GetMapping("/reportedPlants")
  @Operation(
      summary = "Lists the total number of plants planted at a planting site and in each stratum.",
      description = "The totals are based on nursery withdrawals.",
  )
  fun listPlantingSiteReportedPlants(
      @RequestParam //
      organizationId: OrganizationId?,
      @RequestParam //
      projectId: ProjectId?,
  ): ListPlantingSiteReportedPlantsResponsePayload {
    val totals =
        if (organizationId != null) {
          plantingSiteStore.countReportedPlantsForOrganization(organizationId)
        } else if (projectId != null) {
          plantingSiteStore.countReportedPlantsForProject(projectId)
        } else {
          throw IllegalArgumentException("One of organizationId or projectId must be provided.")
        }

    return ListPlantingSiteReportedPlantsResponsePayload(
        totals.map { PlantingSiteReportedPlantsPayload(it) }
    )
  }

  @GetMapping("/{id}/reportedPlants")
  @Operation(
      summary = "Gets the total number of plants planted at a planting site and in each stratum.",
      description = "The totals are based on nursery withdrawals.",
  )
  fun getPlantingSiteReportedPlants(
      @PathVariable id: PlantingSiteId,
  ): GetPlantingSiteReportedPlantsResponsePayload {
    val totals = plantingSiteStore.countReportedPlants(id)

    return GetPlantingSiteReportedPlantsResponsePayload(PlantingSiteReportedPlantsPayload(totals))
  }

  @Operation(summary = "Creates a new planting site.")
  @PostMapping
  fun createPlantingSite(
      @RequestBody payload: CreatePlantingSiteRequestPayload
  ): CreatePlantingSiteResponsePayload {
    payload.validate()

    val plantingSeasons = payload.plantingSeasons?.map { it.toModel() } ?: emptyList()

    val model =
        plantingSiteStore.createPlantingSite(payload.toModel(), plantingSeasons = plantingSeasons)
    return CreatePlantingSiteResponsePayload(model.id)
  }

  @Operation(summary = "Validates the definition of a new planting site.")
  @PostMapping("/validate")
  fun validatePlantingSite(
      @RequestBody payload: CreatePlantingSiteRequestPayload
  ): ValidatePlantingSiteResponsePayload {
    payload.validate()

    val problems = payload.toModel().validate()
    val problemPayloads = problems?.map { PlantingSiteValidationProblemPayload(it) } ?: emptyList()

    return ValidatePlantingSiteResponsePayload(
        isValid = problemPayloads.isEmpty(),
        problems = problemPayloads,
    )
  }

  @Operation(summary = "Updates information about an existing planting site.")
  @PutMapping("/{id}")
  fun updatePlantingSite(
      @PathVariable("id") id: PlantingSiteId,
      @RequestBody payload: UpdatePlantingSiteRequestPayload,
  ): SimpleSuccessResponsePayload {
    val plantingSeasons = payload.plantingSeasons?.map { it.toModel() } ?: emptyList()

    plantingSiteStore.updatePlantingSite(id, plantingSeasons, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse409(
      description = "The planting site is in use, e.g., there are plantings allocated to the site."
  )
  @Operation(
      summary = "Deletes a planting site.",
      description = "Planting site should not have any plantings.",
  )
  @DeleteMapping("/{id}")
  fun deletePlantingSite(@PathVariable("id") id: PlantingSiteId): SimpleSuccessResponsePayload {
    plantingSiteService.deletePlantingSite(id)
    return SimpleSuccessResponsePayload()
  }
}

data class MonitoringPlotPayload(
    val boundary: Polygon,
    val elevationMeters: BigDecimal?,
    val id: MonitoringPlotId,
    val isAdHoc: Boolean,
    val isAvailable: Boolean,
    val plotNumber: Long,
    val sizeMeters: Int,
) {
  constructor(
      model: MonitoringPlotModel
  ) : this(
      boundary = model.boundary,
      elevationMeters = model.elevationMeters,
      id = model.id,
      isAdHoc = model.isAdHoc,
      isAvailable = model.isAvailable,
      plotNumber = model.plotNumber,
      sizeMeters = model.sizeMeters,
  )
}

data class MonitoringPlotHistoryPayload(
    val boundary: Polygon,
    val id: MonitoringPlotHistoryId,
    val monitoringPlotId: MonitoringPlotId,
    val sizeMeters: Int,
) {
  constructor(
      model: MonitoringPlotHistoryModel
  ) : this(
      boundary = model.boundary,
      id = model.id,
      monitoringPlotId = model.monitoringPlotId,
      sizeMeters = model.sizeMeters,
  )
}

// response payload
@Schema(description = "Use SubstratumResponsePayload instead", deprecated = true)
data class PlantingSubzonePayload(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val fullName: String,
    val id: SubstratumId,
    val latestObservationCompletedTime: Instant?,
    val latestObservationId: ObservationId?,
    val monitoringPlots: List<MonitoringPlotPayload>,
    val name: String,
    val observedTime: Instant?,
    val plantingCompleted: Boolean,
    val plantingCompletedTime: Instant?,
) {
  constructor(
      model: ExistingSubstratumModel
  ) : this(
      areaHa = model.areaHa,
      boundary = model.boundary,
      fullName = model.fullName,
      id = model.id,
      latestObservationCompletedTime = model.latestObservationCompletedTime,
      latestObservationId = model.latestObservationId,
      monitoringPlots = model.monitoringPlots.map { MonitoringPlotPayload(it) },
      name = model.name,
      observedTime = model.observedTime,
      plantingCompleted = model.plantingCompletedTime != null,
      plantingCompletedTime = model.plantingCompletedTime,
  )
}

data class SubstratumResponsePayload(
    @Schema(description = "Area of substratum in hectares.") //
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val fullName: String,
    val id: SubstratumId,
    val latestObservationCompletedTime: Instant?,
    val latestObservationId: ObservationId?,
    val monitoringPlots: List<MonitoringPlotPayload>,
    val name: String,
    @Schema(description = "When any monitoring plot in the substratum was most recently observed.")
    val observedTime: Instant?,
    val plantingCompleted: Boolean,
    @Schema(description = "When planting of the substratum was marked as completed.")
    val plantingCompletedTime: Instant?,
) {
  constructor(
      model: ExistingSubstratumModel
  ) : this(
      areaHa = model.areaHa,
      boundary = model.boundary,
      fullName = model.fullName,
      id = model.id,
      latestObservationCompletedTime = model.latestObservationCompletedTime,
      latestObservationId = model.latestObservationId,
      monitoringPlots = model.monitoringPlots.map { MonitoringPlotPayload(it) },
      name = model.name,
      observedTime = model.observedTime,
      plantingCompleted = model.plantingCompletedTime != null,
      plantingCompletedTime = model.plantingCompletedTime,
  )
}

// response payload
@Schema(description = "Use StratumResponsePayload instead", deprecated = true)
data class PlantingZonePayload(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val boundaryModifiedTime: Instant,
    val id: StratumId,
    val latestObservationCompletedTime: Instant?,
    val latestObservationId: ObservationId?,
    val name: String,
    val numPermanentPlots: Int,
    val numTemporaryPlots: Int,
    val plantingSubzones: List<PlantingSubzonePayload>,
    val targetPlantingDensity: BigDecimal,
) {
  constructor(
      model: ExistingStratumModel
  ) : this(
      model.areaHa,
      model.boundary,
      model.boundaryModifiedTime,
      model.id,
      latestObservationCompletedTime = model.latestObservationCompletedTime,
      latestObservationId = model.latestObservationId,
      model.name,
      model.numPermanentPlots,
      model.numTemporaryPlots,
      model.substrata.map { PlantingSubzonePayload(it) },
      model.targetPlantingDensity,
  )
}

data class StratumResponsePayload(
    @Schema(description = "Area of stratum in hectares.") //
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    @Schema(
        description =
            "When the boundary of this stratum was last modified. Modifications of other " +
                "attributes of the stratum do not cause this timestamp to change."
    )
    val boundaryModifiedTime: Instant,
    val id: StratumId,
    val latestObservationCompletedTime: Instant?,
    val latestObservationId: ObservationId?,
    val name: String,
    val numPermanentPlots: Int,
    val numTemporaryPlots: Int,
    val substrata: List<SubstratumResponsePayload>,
    val targetPlantingDensity: BigDecimal,
) {
  constructor(
      model: ExistingStratumModel
  ) : this(
      model.areaHa,
      model.boundary,
      model.boundaryModifiedTime,
      model.id,
      latestObservationCompletedTime = model.latestObservationCompletedTime,
      latestObservationId = model.latestObservationId,
      model.name,
      model.numPermanentPlots,
      model.numTemporaryPlots,
      model.substrata.map { SubstratumResponsePayload(it) },
      model.targetPlantingDensity,
  )
}

data class PlantingSeasonPayload(
    val endDate: LocalDate,
    val id: PlantingSeasonId,
    val startDate: LocalDate,
) {
  constructor(
      model: ExistingPlantingSeasonModel
  ) : this(
      endDate = model.endDate,
      id = model.id,
      startDate = model.startDate,
  )
}

// response payload
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class PlantingSitePayload(
    val adHocPlots: List<MonitoringPlotPayload>,
    @Schema(description = "Area of planting site in hectares. Only present if the site has strata.")
    val areaHa: BigDecimal?,
    val boundary: MultiPolygon?,
    val countryCode: String?,
    val description: String?,
    val exclusion: MultiPolygon?,
    val id: PlantingSiteId,
    val latestObservationCompletedTime: Instant?,
    val latestObservationId: ObservationId?,
    val name: String,
    val organizationId: OrganizationId,
    val plantingSeasons: List<PlantingSeasonPayload>,
    @Schema(description = "Use strata instead", deprecated = true)
    val plantingZones: List<PlantingZonePayload>?,
    val projectId: ProjectId? = null,
    val strata: List<StratumResponsePayload>?,
    val survivalRateIncludesTempPlots: Boolean?,
    val timeZone: ZoneId?,
) {
  constructor(
      model: ExistingPlantingSiteModel
  ) : this(
      adHocPlots = model.adHocPlots.map { MonitoringPlotPayload(it) },
      areaHa = model.areaHa,
      boundary = model.boundary,
      countryCode = model.countryCode,
      description = model.description,
      exclusion = model.exclusion,
      id = model.id,
      latestObservationCompletedTime = model.latestObservationCompletedTime,
      latestObservationId = model.latestObservationId,
      name = model.name,
      organizationId = model.organizationId,
      plantingSeasons = model.plantingSeasons.map { PlantingSeasonPayload(it) },
      plantingZones = model.strata.map { PlantingZonePayload(it) },
      projectId = model.projectId,
      strata = model.strata.map { StratumResponsePayload(it) },
      survivalRateIncludesTempPlots = model.survivalRateIncludesTempPlots,
      timeZone = model.timeZone,
  )
}

// response payload
@Schema(description = "Use SubstratumHistoryResponsePayload instead", deprecated = true)
data class PlantingSubzoneHistoryPayload(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val fullName: String,
    val id: SubstratumHistoryId,
    val monitoringPlots: List<MonitoringPlotHistoryPayload>,
    val name: String,
    val plantingSubzoneId: SubstratumId?,
) {
  constructor(
      model: SubstratumHistoryModel
  ) : this(
      areaHa = model.areaHa,
      boundary = model.boundary,
      fullName = model.fullName,
      id = model.id,
      monitoringPlots = model.monitoringPlots.map { MonitoringPlotHistoryPayload(it) },
      name = model.name,
      plantingSubzoneId = model.substratumId,
  )
}

data class SubstratumHistoryResponsePayload(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val fullName: String,
    val id: SubstratumHistoryId,
    val monitoringPlots: List<MonitoringPlotHistoryPayload>,
    val name: String,
    @Schema(description = "ID of substratum if it exists in the current version of the site.")
    val substratumId: SubstratumId?,
) {
  constructor(
      model: SubstratumHistoryModel
  ) : this(
      areaHa = model.areaHa,
      boundary = model.boundary,
      fullName = model.fullName,
      id = model.id,
      monitoringPlots = model.monitoringPlots.map { MonitoringPlotHistoryPayload(it) },
      name = model.name,
      substratumId = model.substratumId,
  )
}

// response payload
@Schema(description = "Use StratumHistoryResponsePayload instead", deprecated = true)
data class PlantingZoneHistoryPayload(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val id: StratumHistoryId,
    val name: String,
    val plantingSubzones: List<PlantingSubzoneHistoryPayload>,
    val plantingZoneId: StratumId?,
) {
  constructor(
      model: StratumHistoryModel
  ) : this(
      areaHa = model.areaHa,
      boundary = model.boundary,
      id = model.id,
      name = model.name,
      plantingSubzones = model.substrata.map { PlantingSubzoneHistoryPayload(it) },
      plantingZoneId = model.stratumId,
  )
}

data class StratumHistoryResponsePayload(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val id: StratumHistoryId,
    val name: String,
    val substrata: List<SubstratumHistoryResponsePayload>,
    @Schema(description = "ID of stratum if it exists in the current version of the site.")
    val stratumId: StratumId?,
) {
  constructor(
      model: StratumHistoryModel
  ) : this(
      areaHa = model.areaHa,
      boundary = model.boundary,
      id = model.id,
      name = model.name,
      substrata = model.substrata.map { SubstratumHistoryResponsePayload(it) },
      stratumId = model.stratumId,
  )
}

// response payload
data class PlantingSiteHistoryPayload(
    val areaHa: BigDecimal?,
    val boundary: MultiPolygon,
    val createdTime: Instant,
    val exclusion: MultiPolygon? = null,
    val id: PlantingSiteHistoryId,
    val plantingSiteId: PlantingSiteId,
    @Schema(description = "Use strata instead", deprecated = true)
    val plantingZones: List<PlantingZoneHistoryPayload>,
    val strata: List<StratumHistoryResponsePayload>,
) {
  constructor(
      model: PlantingSiteHistoryModel
  ) : this(
      areaHa = model.areaHa,
      boundary = model.boundary,
      createdTime = model.createdTime,
      exclusion = model.exclusion,
      id = model.id,
      plantingSiteId = model.plantingSiteId,
      plantingZones = model.strata.map { PlantingZoneHistoryPayload(it) },
      strata = model.strata.map { StratumHistoryResponsePayload(it) },
  )
}

// response payload
@Schema(description = "Use SubstratumReportedPlantsResponsePayload instead", deprecated = true)
data class PlantingSubzoneReportedPlantsPayload(
    val id: SubstratumId,
    val plantsSinceLastObservation: Int,
    val species: List<ReportedSpeciesPayload>,
    val totalPlants: Int,
    val totalSpecies: Int,
) {
  constructor(
      substratumTotals: PlantingSiteReportedPlantTotals.Substratum
  ) : this(
      id = substratumTotals.id,
      plantsSinceLastObservation = substratumTotals.plantsSinceLastObservation,
      species = substratumTotals.species.map { ReportedSpeciesPayload(it) },
      totalPlants = substratumTotals.totalPlants,
      totalSpecies = substratumTotals.totalSpecies,
  )
}

data class SubstratumReportedPlantsResponsePayload(
    val id: SubstratumId,
    val plantsSinceLastObservation: Int,
    val species: List<ReportedSpeciesPayload>,
    val totalPlants: Int,
    val totalSpecies: Int,
) {
  constructor(
      substratumTotals: PlantingSiteReportedPlantTotals.Substratum
  ) : this(
      id = substratumTotals.id,
      plantsSinceLastObservation = substratumTotals.plantsSinceLastObservation,
      species = substratumTotals.species.map { ReportedSpeciesPayload(it) },
      totalPlants = substratumTotals.totalPlants,
      totalSpecies = substratumTotals.totalSpecies,
  )
}

// response payload
@Schema(description = "Use StratumReportedPlantsResponsePayload instead", deprecated = true)
data class PlantingZoneReportedPlantsPayload(
    val id: StratumId,
    val plantsSinceLastObservation: Int,
    val plantingSubzones: List<PlantingSubzoneReportedPlantsPayload>,
    val progressPercent: Int,
    val species: List<ReportedSpeciesPayload>,
    val totalPlants: Int,
    val totalSpecies: Int,
) {
  constructor(
      stratumTotals: PlantingSiteReportedPlantTotals.Stratum
  ) : this(
      id = stratumTotals.id,
      plantsSinceLastObservation = stratumTotals.plantsSinceLastObservation,
      plantingSubzones = stratumTotals.substrata.map { PlantingSubzoneReportedPlantsPayload(it) },
      progressPercent = stratumTotals.progressPercent,
      species = stratumTotals.species.map { ReportedSpeciesPayload(it) },
      totalPlants = stratumTotals.totalPlants,
      totalSpecies = stratumTotals.totalSpecies,
  )
}

data class StratumReportedPlantsResponsePayload(
    val id: StratumId,
    val plantsSinceLastObservation: Int,
    val substrata: List<SubstratumReportedPlantsResponsePayload>,
    val progressPercent: Int,
    val species: List<ReportedSpeciesPayload>,
    val totalPlants: Int,
    val totalSpecies: Int,
) {
  constructor(
      stratumTotals: PlantingSiteReportedPlantTotals.Stratum
  ) : this(
      id = stratumTotals.id,
      plantsSinceLastObservation = stratumTotals.plantsSinceLastObservation,
      substrata = stratumTotals.substrata.map { SubstratumReportedPlantsResponsePayload(it) },
      progressPercent = stratumTotals.progressPercent,
      species = stratumTotals.species.map { ReportedSpeciesPayload(it) },
      totalPlants = stratumTotals.totalPlants,
      totalSpecies = stratumTotals.totalSpecies,
  )
}

// response payload
data class PlantingSiteReportedPlantsPayload(
    val id: PlantingSiteId,
    @Schema(description = "Use strata instead", deprecated = true)
    val plantingZones: List<PlantingZoneReportedPlantsPayload>,
    val plantsSinceLastObservation: Int,
    val progressPercent: Int?,
    val species: List<ReportedSpeciesPayload>,
    val strata: List<StratumReportedPlantsResponsePayload>,
    val totalPlants: Int,
) {
  constructor(
      totals: PlantingSiteReportedPlantTotals
  ) : this(
      id = totals.id,
      plantingZones = totals.strata.map { PlantingZoneReportedPlantsPayload(it) },
      plantsSinceLastObservation = totals.plantsSinceLastObservation,
      progressPercent = totals.progressPercent,
      species = totals.species.map { ReportedSpeciesPayload(it) },
      strata = totals.strata.map { StratumReportedPlantsResponsePayload(it) },
      totalPlants = totals.totalPlants,
  )
}

data class ReportedSpeciesPayload(
    val id: SpeciesId,
    val plantsSinceLastObservation: Int,
    val totalPlants: Int,
) {
  constructor(
      species: PlantingSiteReportedPlantTotals.Species
  ) : this(
      id = species.id,
      plantsSinceLastObservation = species.plantsSinceLastObservation,
      totalPlants = species.totalPlants,
  )
}

data class NewPlantingSeasonPayload(
    val endDate: LocalDate,
    val startDate: LocalDate,
) {
  fun toModel() = UpdatedPlantingSeasonModel(endDate = endDate, startDate = startDate)
}

data class UpdatedPlantingSeasonPayload(
    val endDate: LocalDate,
    @Schema(
        description =
            "If present, the start and end dates of an existing planting season will be updated. " +
                "Otherwise a new planting season will be created."
    )
    val id: PlantingSeasonId? = null,
    val startDate: LocalDate,
) {
  fun toModel() = UpdatedPlantingSeasonModel(endDate = endDate, id = id, startDate = startDate)
}

data class NewPlantingSubzonePayload(
    @Schema(oneOf = [MultiPolygon::class, Polygon::class]) //
    val boundary: Geometry,
    @Schema(
        description =
            "Name of this substratum. Two substrata in the same stratum may not have the same name, " +
                "but using the same substratum name in different strata is valid."
    )
    val name: String,
) {
  fun validate() {
    if (boundary !is MultiPolygon && boundary !is Polygon) {
      throw IllegalArgumentException("Substratum boundaries must be Polygon or MultiPolygon")
    }
  }

  fun toModel(zoneName: String, exclusion: MultiPolygon?): NewSubstratumModel {
    return SubstratumModel.create(
        boundary = boundary.toMultiPolygon(),
        exclusion = exclusion,
        fullName = "$zoneName-$name",
        name = name,
    )
  }
}

data class NewPlantingZonePayload(
    @Schema(oneOf = [MultiPolygon::class, Polygon::class]) //
    val boundary: Geometry,
    @Schema(
        description =
            "Name of this stratum. Two strata in the same planting site may not have the same name."
    )
    val name: String,
    val plantingSubzones: List<NewPlantingSubzonePayload>?,
    val targetPlantingDensity: BigDecimal?,
) {
  fun validate() {
    if (boundary !is MultiPolygon && boundary !is Polygon) {
      throw IllegalArgumentException("Stratum boundaries must be Polygon or MultiPolygon")
    }

    plantingSubzones?.forEach { it.validate() }
  }

  fun toModel(exclusion: MultiPolygon?): NewStratumModel {
    return StratumModel.create(
        boundary = boundary.toMultiPolygon(),
        exclusion = exclusion,
        name = name,
        targetPlantingDensity =
            targetPlantingDensity ?: StratumModel.DEFAULT_TARGET_PLANTING_DENSITY,
        substrata = plantingSubzones?.map { it.toModel(name, exclusion) } ?: emptyList(),
    )
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PlantingSiteValidationProblemPayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "If the problem is a conflict between two strata or two substrata, " +
                        "the list of the conflicting stratum or substratum names."
            )
    )
    val conflictsWith: Set<String>?,
    @Schema(description = "If the problem relates to a particular stratum, its name.")
    val plantingZone: String?,
    @Schema(
        description =
            "If the problem relates to a particular substratum, its name. If this is present, " +
                "plantingZone will also be present and will be the name of the stratum that " +
                "contains this substratum."
    )
    val plantingSubzone: String?,
    val problemType: PlantingSiteValidationFailureType,
) {
  constructor(
      model: PlantingSiteValidationFailure
  ) : this(model.conflictsWith, model.stratumName, model.substratumName, model.type)
}

data class CreatePlantingSiteRequestPayload(
    @Schema(oneOf = [MultiPolygon::class, Polygon::class]) //
    val boundary: Geometry? = null,
    val description: String? = null,
    @Schema(oneOf = [MultiPolygon::class, Polygon::class]) //
    val exclusion: Geometry? = null,
    val name: String,
    val organizationId: OrganizationId,
    val plantingSeasons: List<NewPlantingSeasonPayload>? = null,
    @Schema(
        description =
            "List of strata to create. If present and not empty, \"boundary\" must also " +
                "be specified."
    )
    val plantingZones: List<NewPlantingZonePayload>? = null,
    val projectId: ProjectId? = null,
    val timeZone: ZoneId?,
) {
  fun validate() {
    if (boundary != null && boundary !is MultiPolygon && boundary !is Polygon) {
      throw IllegalArgumentException("Planting site boundary must be Polygon or MultiPolygon")
    }

    if (exclusion != null && exclusion !is MultiPolygon && exclusion !is Polygon) {
      throw IllegalArgumentException("Exclusion area must be Polygon or MultiPolygon")
    }

    plantingZones?.forEach { it.validate() }
  }

  fun toModel(): NewPlantingSiteModel {
    val exclusionMultiPolygon = exclusion?.toMultiPolygon()

    return PlantingSiteModel.create(
        boundary = boundary?.toMultiPolygon(),
        description = description,
        exclusion = exclusionMultiPolygon,
        name = name,
        organizationId = organizationId,
        strata = plantingZones?.map { it.toModel(exclusionMultiPolygon) } ?: emptyList(),
        projectId = projectId,
        timeZone = timeZone,
    )
  }
}

data class CreatePlantingSiteResponsePayload(val id: PlantingSiteId) : SuccessResponsePayload

data class GetPlantingSiteHistoryResponsePayload(val site: PlantingSiteHistoryPayload) :
    SuccessResponsePayload

data class ListPlantingSiteHistoriesResponsePayload(
    val histories: List<PlantingSiteHistoryPayload>
) : SuccessResponsePayload

data class GetPlantingSiteResponsePayload(val site: PlantingSitePayload) : SuccessResponsePayload

data class GetPlantingSiteReportedPlantsResponsePayload(
    val site: PlantingSiteReportedPlantsPayload
) : SuccessResponsePayload

data class ListPlantingSitesResponsePayload(val sites: List<PlantingSitePayload>) :
    SuccessResponsePayload

data class ListPlantingSiteReportedPlantsResponsePayload(
    val sites: List<PlantingSiteReportedPlantsPayload>
) : SuccessResponsePayload

data class UpdatePlantingSiteRequestPayload(
    @Schema(description = "Site boundary. Ignored if this is a detailed planting site.")
    val boundary: MultiPolygon? = null,
    val description: String? = null,
    val name: String,
    val plantingSeasons: List<UpdatedPlantingSeasonPayload>? = null,
    val projectId: ProjectId? = null,
    val survivalRateIncludesTempPlots: Boolean? = null,
    val timeZone: ZoneId?,
) {
  fun applyTo(model: ExistingPlantingSiteModel) =
      model.copy(
          boundary = boundary,
          description = description?.ifBlank { null },
          name = name,
          projectId = projectId,
          timeZone = timeZone,
          survivalRateIncludesTempPlots = survivalRateIncludesTempPlots ?: false,
      )
}

data class ValidatePlantingSiteResponsePayload(
    @Schema(description = "True if the request was valid.") //
    val isValid: Boolean,
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "List of validation problems found, if any. Empty if the request is valid."
            )
    )
    val problems: List<PlantingSiteValidationProblemPayload>,
) : SuccessResponsePayload
