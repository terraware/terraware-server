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
import com.terraformation.backend.db.tracking.PlantingSubzoneHistoryId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneHistoryId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.tracking.PlantingSiteService
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.ExistingPlantingSeasonModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSubzoneModel
import com.terraformation.backend.tracking.model.ExistingPlantingZoneModel
import com.terraformation.backend.tracking.model.MonitoringPlotHistoryModel
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.NewPlantingSiteModel
import com.terraformation.backend.tracking.model.NewPlantingSubzoneModel
import com.terraformation.backend.tracking.model.NewPlantingZoneModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteHistoryModel
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.PlantingSiteValidationFailure
import com.terraformation.backend.tracking.model.PlantingSiteValidationFailureType
import com.terraformation.backend.tracking.model.PlantingSubzoneHistoryModel
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneHistoryModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.tracking.model.UpdatedPlantingSeasonModel
import com.terraformation.backend.util.toMultiPolygon
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
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
      description =
          "The list can optionally contain information about planting zones and subzones.",
  )
  fun listPlantingSites(
      @RequestParam //
      organizationId: OrganizationId,
      @RequestParam
      @Schema(
          description = "If true, include planting zones and subzones for each site.",
          defaultValue = "false",
      )
      full: Boolean?,
  ): ListPlantingSitesResponsePayload {
    val depth = if (full == true) PlantingSiteDepth.Plot else PlantingSiteDepth.Site
    val models = plantingSiteStore.fetchSitesByOrganizationId(organizationId, depth)
    val payloads = models.map { PlantingSitePayload(it) }
    return ListPlantingSitesResponsePayload(payloads)
  }

  @GetMapping("/{id}")
  @Operation(
      summary = "Gets information about a specific planting site.",
      description = "Includes information about the site's planting zones and subzones.",
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
      summary =
          "Lists the total number of plants planted at a planting site and in each planting zone.",
      description = "The totals are based on nursery withdrawals.",
  )
  fun listPlantingSiteReportedPlants(
      @RequestParam //
      organizationId: OrganizationId,
  ): ListPlantingSiteReportedPlantsResponsePayload {
    val totals = plantingSiteStore.countReportedPlantsForOrganization(organizationId)

    return ListPlantingSiteReportedPlantsResponsePayload(
        totals.map { PlantingSiteReportedPlantsPayload(it) }
    )
  }

  @GetMapping("/{id}/reportedPlants")
  @Operation(
      summary =
          "Gets the total number of plants planted at a planting site and in each planting zone.",
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

data class PlantingSubzonePayload(
    @Schema(description = "Area of planting subzone in hectares.") //
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val fullName: String,
    val id: PlantingSubzoneId,
    val latestObservationCompletedTime: Instant?,
    val latestObservationId: ObservationId?,
    val monitoringPlots: List<MonitoringPlotPayload>,
    val name: String,
    @Schema(
        description = "When any monitoring plot in the planting subzone was most recently observed."
    )
    val observedTime: Instant?,
    val plantingCompleted: Boolean,
    @Schema(description = "When planting of the planting subzone was marked as completed.")
    val plantingCompletedTime: Instant?,
) {
  constructor(
      model: ExistingPlantingSubzoneModel
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

data class PlantingZonePayload(
    @Schema(description = "Area of planting zone in hectares.") //
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    @Schema(
        description =
            "When the boundary of this planting zone was last modified. Modifications of other " +
                "attributes of the planting zone do not cause this timestamp to change."
    )
    val boundaryModifiedTime: Instant,
    val id: PlantingZoneId,
    val latestObservationCompletedTime: Instant?,
    val latestObservationId: ObservationId?,
    val name: String,
    val numPermanentPlots: Int,
    val numTemporaryPlots: Int,
    val plantingSubzones: List<PlantingSubzonePayload>,
    val targetPlantingDensity: BigDecimal,
) {
  constructor(
      model: ExistingPlantingZoneModel
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
      model.plantingSubzones.map { PlantingSubzonePayload(it) },
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

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class PlantingSitePayload(
    val adHocPlots: List<MonitoringPlotPayload>,
    @Schema(
        description =
            "Area of planting site in hectares. Only present if the site has planting zones."
    )
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
    val plantingZones: List<PlantingZonePayload>?,
    val projectId: ProjectId? = null,
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
      plantingZones = model.plantingZones.map { PlantingZonePayload(it) },
      projectId = model.projectId,
      timeZone = model.timeZone,
  )
}

data class PlantingSubzoneHistoryPayload(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val fullName: String,
    val id: PlantingSubzoneHistoryId,
    val monitoringPlots: List<MonitoringPlotHistoryPayload>,
    val name: String,
    @Schema(description = "ID of planting subzone if it exists in the current version of the site.")
    val plantingSubzoneId: PlantingSubzoneId?,
) {
  constructor(
      model: PlantingSubzoneHistoryModel
  ) : this(
      areaHa = model.areaHa,
      boundary = model.boundary,
      fullName = model.fullName,
      id = model.id,
      monitoringPlots = model.monitoringPlots.map { MonitoringPlotHistoryPayload(it) },
      name = model.name,
      plantingSubzoneId = model.plantingSubzoneId,
  )
}

data class PlantingZoneHistoryPayload(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val id: PlantingZoneHistoryId,
    val name: String,
    val plantingSubzones: List<PlantingSubzoneHistoryPayload>,
    @Schema(description = "ID of planting zone if it exists in the current version of the site.")
    val plantingZoneId: PlantingZoneId?,
) {
  constructor(
      model: PlantingZoneHistoryModel
  ) : this(
      areaHa = model.areaHa,
      boundary = model.boundary,
      id = model.id,
      name = model.name,
      plantingSubzones = model.plantingSubzones.map { PlantingSubzoneHistoryPayload(it) },
      plantingZoneId = model.plantingZoneId,
  )
}

data class PlantingSiteHistoryPayload(
    val areaHa: BigDecimal?,
    val boundary: MultiPolygon,
    val createdTime: Instant,
    val exclusion: MultiPolygon? = null,
    val id: PlantingSiteHistoryId,
    val plantingSiteId: PlantingSiteId,
    val plantingZones: List<PlantingZoneHistoryPayload>,
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
      plantingZones = model.plantingZones.map { PlantingZoneHistoryPayload(it) },
  )
}

data class PlantingSubzoneReportedPlantsPayload(
    val id: PlantingSubzoneId,
    val plantsSinceLastObservation: Int,
    val species: List<ReportedSpeciesPayload>,
    val totalPlants: Int,
    val totalSpecies: Int,
) {
  constructor(
      subzoneTotals: PlantingSiteReportedPlantTotals.PlantingSubzone
  ) : this(
      id = subzoneTotals.id,
      plantsSinceLastObservation = subzoneTotals.plantsSinceLastObservation,
      species = subzoneTotals.species.map { ReportedSpeciesPayload(it) },
      totalPlants = subzoneTotals.totalPlants,
      totalSpecies = subzoneTotals.totalSpecies,
  )
}

data class PlantingZoneReportedPlantsPayload(
    val id: PlantingZoneId,
    val plantsSinceLastObservation: Int,
    val plantingSubzones: List<PlantingSubzoneReportedPlantsPayload>,
    val progressPercent: Int,
    val species: List<ReportedSpeciesPayload>,
    val totalPlants: Int,
    val totalSpecies: Int,
) {
  constructor(
      zoneTotals: PlantingSiteReportedPlantTotals.PlantingZone
  ) : this(
      id = zoneTotals.id,
      plantsSinceLastObservation = zoneTotals.plantsSinceLastObservation,
      plantingSubzones =
          zoneTotals.plantingSubzones.map { PlantingSubzoneReportedPlantsPayload(it) },
      progressPercent = zoneTotals.progressPercent,
      species = zoneTotals.species.map { ReportedSpeciesPayload(it) },
      totalPlants = zoneTotals.totalPlants,
      totalSpecies = zoneTotals.totalSpecies,
  )
}

data class PlantingSiteReportedPlantsPayload(
    val id: PlantingSiteId,
    val plantingZones: List<PlantingZoneReportedPlantsPayload>,
    val plantsSinceLastObservation: Int,
    val progressPercent: Int?,
    val species: List<ReportedSpeciesPayload>,
    val totalPlants: Int,
) {
  constructor(
      totals: PlantingSiteReportedPlantTotals
  ) : this(
      id = totals.id,
      plantingZones = totals.plantingZones.map { PlantingZoneReportedPlantsPayload(it) },
      plantsSinceLastObservation = totals.plantsSinceLastObservation,
      progressPercent = totals.progressPercent,
      species = totals.species.map { ReportedSpeciesPayload(it) },
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
            "Name of this planting subzone. Two subzones in the same planting zone may not have " +
                "the same name, but using the same subzone name in different planting zones is " +
                "valid."
    )
    val name: String,
) {
  fun validate() {
    if (boundary !is MultiPolygon && boundary !is Polygon) {
      throw IllegalArgumentException("Planting subzone boundaries must be Polygon or MultiPolygon")
    }
  }

  fun toModel(zoneName: String, exclusion: MultiPolygon?): NewPlantingSubzoneModel {
    return PlantingSubzoneModel.create(
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
            "Name of this planting zone. Two zones in the same planting site may not have the " +
                "same name."
    )
    val name: String,
    val plantingSubzones: List<NewPlantingSubzonePayload>?,
    val targetPlantingDensity: BigDecimal?,
) {
  fun validate() {
    if (boundary !is MultiPolygon && boundary !is Polygon) {
      throw IllegalArgumentException("Planting zone boundaries must be Polygon or MultiPolygon")
    }

    plantingSubzones?.forEach { it.validate() }
  }

  fun toModel(exclusion: MultiPolygon?): NewPlantingZoneModel {
    return PlantingZoneModel.create(
        boundary = boundary.toMultiPolygon(),
        exclusion = exclusion,
        name = name,
        targetPlantingDensity =
            targetPlantingDensity ?: PlantingZoneModel.DEFAULT_TARGET_PLANTING_DENSITY,
        plantingSubzones = plantingSubzones?.map { it.toModel(name, exclusion) } ?: emptyList(),
    )
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PlantingSiteValidationProblemPayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "If the problem is a conflict between two planting zones or two subzones, " +
                        "the list of the conflicting zone or subzone names."
            )
    )
    val conflictsWith: Set<String>?,
    @Schema(description = "If the problem relates to a particular planting zone, its name.")
    val plantingZone: String?,
    @Schema(
        description =
            "If the problem relates to a particular subzone, its name. If this is present, " +
                "plantingZone will also be present and will be the name of the zone that " +
                "contains this subzone."
    )
    val plantingSubzone: String?,
    val problemType: PlantingSiteValidationFailureType,
) {
  constructor(
      model: PlantingSiteValidationFailure
  ) : this(model.conflictsWith, model.zoneName, model.subzoneName, model.type)
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
            "List of planting zones to create. If present and not empty, \"boundary\" must also " +
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
        plantingZones = plantingZones?.map { it.toModel(exclusionMultiPolygon) } ?: emptyList(),
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
