package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.tracking.PlantingSiteService
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.ExistingPlantingSeasonModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.tracking.model.UpdatedPlantingSeasonModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.locationtech.jts.geom.MultiPolygon
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
          "The list can optionally contain information about planting zones and subzones.")
  fun listPlantingSites(
      @RequestParam //
      organizationId: OrganizationId,
      @RequestParam
      @Schema(
          description = "If true, include planting zones and subzones for each site.",
          defaultValue = "false")
      full: Boolean?
  ): ListPlantingSitesResponsePayload {
    val depth = if (full == true) PlantingSiteDepth.Subzone else PlantingSiteDepth.Site
    val models = plantingSiteStore.fetchSitesByOrganizationId(organizationId, depth)
    val payloads = models.map { PlantingSitePayload(it) }
    return ListPlantingSitesResponsePayload(payloads)
  }

  @GetMapping("/{id}")
  @Operation(
      summary = "Gets information about a specific planting site.",
      description = "Includes information about the site's planting zones and subzones.")
  fun getPlantingSite(
      @PathVariable("id") id: PlantingSiteId,
  ): GetPlantingSiteResponsePayload {
    val model = plantingSiteStore.fetchSiteById(id, PlantingSiteDepth.Subzone)
    return GetPlantingSiteResponsePayload(PlantingSitePayload(model))
  }

  @GetMapping("/{id}/reportedPlants")
  @Operation(
      summary =
          "Gets the total number of plants planted at a planting site and in each planting zone.",
      description = "The totals are based on nursery withdrawals.")
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
    val plantingSeasons = payload.plantingSeasons?.map { it.toModel() } ?: emptyList()

    val model =
        plantingSiteStore.createPlantingSite(
            boundary = payload.boundary,
            description = payload.description,
            name = payload.name,
            organizationId = payload.organizationId,
            plantingSeasons = plantingSeasons,
            projectId = payload.projectId,
            timeZone = payload.timeZone,
        )
    return CreatePlantingSiteResponsePayload(model.id)
  }

  @Operation(summary = "Updates information about an existing planting site.")
  @PutMapping("/{id}")
  fun updatePlantingSite(
      @PathVariable("id") id: PlantingSiteId,
      @RequestBody payload: UpdatePlantingSiteRequestPayload
  ): SimpleSuccessResponsePayload {
    val plantingSeasons = payload.plantingSeasons?.map { it.toModel() } ?: emptyList()

    plantingSiteStore.updatePlantingSite(id, plantingSeasons, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse409(
      description = "The planting site is in use, e.g., there are plantings allocated to the site.")
  @Operation(
      summary = "Deletes a planting site.",
      description = "Planting site should not have any plantings.")
  @DeleteMapping("/{id}")
  fun deletePlantingSite(@PathVariable("id") id: PlantingSiteId): SimpleSuccessResponsePayload {
    plantingSiteService.deletePlantingSite(id)
    return SimpleSuccessResponsePayload()
  }
}

data class PlantingSubzonePayload(
    @Schema(description = "Area of planting subzone in hectares.") //
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val fullName: String,
    val id: PlantingSubzoneId,
    val name: String,
    val plantingCompleted: Boolean,
    @Schema(description = "When planting of the planting subzone was marked as completed.")
    val plantingCompletedTime: Instant?,
) {
  constructor(
      model: PlantingSubzoneModel
  ) : this(
      model.areaHa,
      model.boundary,
      model.fullName,
      model.id,
      model.name,
      model.plantingCompletedTime != null,
      model.plantingCompletedTime,
  )
}

data class PlantingZonePayload(
    @Schema(description = "Area of planting zone in hectares.") //
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val id: PlantingZoneId,
    val name: String,
    val plantingSubzones: List<PlantingSubzonePayload>,
    val targetPlantingDensity: BigDecimal,
) {
  constructor(
      model: PlantingZoneModel
  ) : this(
      model.areaHa,
      model.boundary,
      model.id,
      model.name,
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
    @Schema(
        description =
            "Area of planting site in hectares. Only present if the site has planting zones.")
    val areaHa: BigDecimal?,
    val boundary: MultiPolygon?,
    val description: String?,
    val exclusion: MultiPolygon?,
    val id: PlantingSiteId,
    val name: String,
    val organizationId: OrganizationId,
    val plantingSeasons: List<PlantingSeasonPayload>,
    val plantingZones: List<PlantingZonePayload>?,
    val projectId: ProjectId? = null,
    val timeZone: ZoneId?,
) {
  constructor(
      model: PlantingSiteModel
  ) : this(
      areaHa = model.areaHa,
      boundary = model.boundary,
      description = model.description,
      exclusion = model.exclusion,
      id = model.id,
      name = model.name,
      organizationId = model.organizationId,
      plantingSeasons = model.plantingSeasons.map { PlantingSeasonPayload(it) },
      plantingZones = model.plantingZones.map { PlantingZonePayload(it) },
      projectId = model.projectId,
      timeZone = model.timeZone,
  )
}

data class PlantingZoneReportedPlantsPayload(
    val id: PlantingZoneId,
    val plantsSinceLastObservation: Int,
    val progressPercent: Int,
    val totalPlants: Int,
) {
  constructor(
      zoneTotals: PlantingSiteReportedPlantTotals.PlantingZone
  ) : this(
      id = zoneTotals.id,
      plantsSinceLastObservation = zoneTotals.plantsSinceLastObservation,
      progressPercent = zoneTotals.progressPercent,
      totalPlants = zoneTotals.totalPlants,
  )
}

data class PlantingSiteReportedPlantsPayload(
    val id: PlantingSiteId,
    val plantingZones: List<PlantingZoneReportedPlantsPayload>,
    val plantsSinceLastObservation: Int,
    val progressPercent: Int?,
    val totalPlants: Int,
) {
  constructor(
      totals: PlantingSiteReportedPlantTotals
  ) : this(
      id = totals.id,
      plantingZones = totals.plantingZones.map { PlantingZoneReportedPlantsPayload(it) },
      plantsSinceLastObservation = totals.plantsSinceLastObservation,
      progressPercent = totals.progressPercent,
      totalPlants = totals.totalPlants,
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
                "Otherwise a new planting season will be created.")
    val id: PlantingSeasonId? = null,
    val startDate: LocalDate,
) {
  fun toModel() = UpdatedPlantingSeasonModel(endDate = endDate, id = id, startDate = startDate)
}

data class CreatePlantingSiteRequestPayload(
    val boundary: MultiPolygon? = null,
    val description: String? = null,
    val name: String,
    val organizationId: OrganizationId,
    val plantingSeasons: List<NewPlantingSeasonPayload>? = null,
    val projectId: ProjectId? = null,
    val timeZone: ZoneId?,
)

data class CreatePlantingSiteResponsePayload(val id: PlantingSiteId) : SuccessResponsePayload

data class GetPlantingSiteResponsePayload(val site: PlantingSitePayload) : SuccessResponsePayload

data class GetPlantingSiteReportedPlantsResponsePayload(
    val site: PlantingSiteReportedPlantsPayload
) : SuccessResponsePayload

data class ListPlantingSitesResponsePayload(val sites: List<PlantingSitePayload>) :
    SuccessResponsePayload

data class UpdatePlantingSiteRequestPayload(
    @Schema(description = "Site boundary. Ignored if this is a detailed planting site.")
    val boundary: MultiPolygon? = null,
    val description: String? = null,
    val name: String,
    val plantingSeasons: List<UpdatedPlantingSeasonPayload>? = null,
    val projectId: ProjectId? = null,
    val timeZone: ZoneId?,
) {
  fun applyTo(model: PlantingSiteModel) =
      model.copy(
          boundary = boundary,
          description = description?.ifBlank { null },
          name = name,
          projectId = projectId,
          timeZone = timeZone,
      )
}
