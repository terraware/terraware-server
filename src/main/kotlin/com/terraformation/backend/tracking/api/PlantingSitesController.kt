package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.math.BigDecimal
import java.time.Instant
import java.time.Month
import java.time.ZoneId
import org.locationtech.jts.geom.MultiPolygon
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
) {
  @GetMapping
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
  fun getPlantingSite(
      @PathVariable("id") id: PlantingSiteId,
  ): GetPlantingSiteResponsePayload {
    val model = plantingSiteStore.fetchSiteById(id, PlantingSiteDepth.Subzone)
    return GetPlantingSiteResponsePayload(PlantingSitePayload(model))
  }

  @GetMapping("/{id}/reportedPlants")
  fun getPlantingSiteReportedPlants(
      @PathVariable id: PlantingSiteId,
  ): GetPlantingSiteReportedPlantsResponsePayload {
    val totals = plantingSiteStore.countReportedPlants(id)

    return GetPlantingSiteReportedPlantsResponsePayload(PlantingSiteReportedPlantsPayload(totals))
  }

  @PostMapping
  fun createPlantingSite(
      @RequestBody payload: CreatePlantingSiteRequestPayload
  ): CreatePlantingSiteResponsePayload {
    val model =
        plantingSiteStore.createPlantingSite(
            description = payload.description,
            name = payload.name,
            organizationId = payload.organizationId,
            timeZone = payload.timeZone,
        )
    return CreatePlantingSiteResponsePayload(model.id)
  }

  @PutMapping("/{id}")
  fun updatePlantingSite(
      @PathVariable("id") id: PlantingSiteId,
      @RequestBody payload: UpdatePlantingSiteRequestPayload
  ): SimpleSuccessResponsePayload {
    plantingSiteStore.updatePlantingSite(id, payload::applyTo)
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

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class PlantingSitePayload(
    @Schema(
        description =
            "Area of planting site in hectares. Only present if the site has planting zones.")
    val areaHa: BigDecimal?,
    val boundary: MultiPolygon?,
    val description: String?,
    val id: PlantingSiteId,
    val name: String,
    val organizationId: OrganizationId,
    @Max(12)
    @Min(1)
    @Schema(description = "What month this site's planting season ends. 1=January.")
    val plantingSeasonEndMonth: Int? = null,
    @Max(12)
    @Min(1)
    @Schema(description = "What month this site's planting season starts. 1=January.")
    val plantingSeasonStartMonth: Int? = null,
    val plantingZones: List<PlantingZonePayload>?,
    val timeZone: ZoneId?,
) {
  constructor(
      model: PlantingSiteModel
  ) : this(
      areaHa = model.areaHa,
      boundary = model.boundary,
      description = model.description,
      id = model.id,
      name = model.name,
      organizationId = model.organizationId,
      plantingSeasonEndMonth = model.plantingSeasonEndMonth?.value,
      plantingSeasonStartMonth = model.plantingSeasonStartMonth?.value,
      plantingZones = model.plantingZones.map { PlantingZonePayload(it) },
      timeZone = model.timeZone,
  )
}

data class PlantingSubzoneReportedPlantsPayload(
    val id: PlantingSubzoneId,
    val plantsSinceLastObservation: Int,
    val totalPlants: Int,
) {
  constructor(
      subzoneTotals: PlantingSiteReportedPlantTotals.PlantingSubzone
  ) : this(
      id = subzoneTotals.id,
      plantsSinceLastObservation = subzoneTotals.plantsSinceLastObservation,
      totalPlants = subzoneTotals.totalPlants,
  )
}

data class PlantingZoneReportedPlantsPayload(
    val id: PlantingZoneId,
    val plantingSubzones: List<PlantingSubzoneReportedPlantsPayload>,
    val plantsSinceLastObservation: Int,
    val progressPercent: Int,
    val totalPlants: Int,
) {
  constructor(
      zoneTotals: PlantingSiteReportedPlantTotals.PlantingZone
  ) : this(
      id = zoneTotals.id,
      plantingSubzones =
          zoneTotals.plantingSubzones.map { PlantingSubzoneReportedPlantsPayload(it) },
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

data class CreatePlantingSiteRequestPayload(
    val description: String? = null,
    val name: String,
    val organizationId: OrganizationId,
    @Max(12)
    @Min(1)
    @Schema(description = "What month this site's planting season ends. 1=January.")
    val plantingSeasonEndMonth: Int? = null,
    @Max(12)
    @Min(1)
    @Schema(description = "What month this site's planting season starts. 1=January.")
    val plantingSeasonStartMonth: Int? = null,
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
    val description: String? = null,
    val name: String,
    @Max(12)
    @Min(1)
    @Schema(description = "What month this site's planting season ends. 1=January.")
    val plantingSeasonEndMonth: Int? = null,
    @Max(12)
    @Min(1)
    @Schema(description = "What month this site's planting season starts. 1=January.")
    val plantingSeasonStartMonth: Int? = null,
    val timeZone: ZoneId?,
) {
  fun applyTo(model: PlantingSiteModel) =
      model.copy(
          description = description?.ifBlank { null },
          name = name,
          plantingSeasonEndMonth = plantingSeasonEndMonth?.let { Month.of(it) },
          plantingSeasonStartMonth = plantingSeasonStartMonth?.let { Month.of(it) },
          timeZone = timeZone,
      )
}
