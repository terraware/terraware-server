package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.PlotId
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.tracking.model.PlotModel
import io.swagger.v3.oas.annotations.media.Schema
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
      @RequestParam(required = false)
      @Schema(
          description = "If true, include planting zones and plots for each site.",
          defaultValue = "false")
      full: Boolean?
  ): ListPlantingSitesResponsePayload {
    val models = plantingSiteStore.fetchSitesByOrganizationId(organizationId, full == true)
    val payloads = models.map { PlantingSitePayload(it) }
    return ListPlantingSitesResponsePayload(payloads)
  }

  @GetMapping("/{id}")
  fun getPlantingSite(
      @PathVariable("id") id: PlantingSiteId,
  ): GetPlantingSiteResponsePayload {
    val model = plantingSiteStore.fetchSiteById(id)
    return GetPlantingSiteResponsePayload(PlantingSitePayload(model))
  }

  @PostMapping
  fun createPlantingSite(
      @RequestBody payload: CreatePlantingSiteRequestPayload
  ): CreatePlantingSiteResponsePayload {
    val model =
        plantingSiteStore.createPlantingSite(
            payload.organizationId, payload.name, payload.description)
    return CreatePlantingSiteResponsePayload(model.id)
  }

  @PutMapping("/{id}")
  fun updatePlantingSite(
      @PathVariable("id") id: PlantingSiteId,
      @RequestBody payload: UpdatePlantingSiteRequestPayload
  ): SimpleSuccessResponsePayload {
    plantingSiteStore.updatePlantingSite(id, payload.name, payload.description)
    return SimpleSuccessResponsePayload()
  }
}

data class PlotPayload(
    val boundary: MultiPolygon,
    val fullName: String,
    val id: PlotId,
    val name: String,
) {
  constructor(model: PlotModel) : this(model.boundary, model.fullName, model.id, model.name)
}

data class PlantingZonePayload(
    val boundary: MultiPolygon,
    val id: PlantingZoneId,
    val name: String,
    val plots: List<PlotPayload>,
) {
  constructor(
      model: PlantingZoneModel
  ) : this(model.boundary, model.id, model.name, model.plots.map { PlotPayload(it) })
}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class PlantingSitePayload(
    val boundary: MultiPolygon?,
    val description: String?,
    val id: PlantingSiteId,
    val name: String,
    val plantingZones: List<PlantingZonePayload>?,
) {
  constructor(
      model: PlantingSiteModel
  ) : this(
      model.boundary,
      model.description,
      model.id,
      model.name,
      model.plantingZones.map { PlantingZonePayload(it) },
  )
}

data class CreatePlantingSiteRequestPayload(
    val description: String? = null,
    val name: String,
    val organizationId: OrganizationId,
)

data class CreatePlantingSiteResponsePayload(val id: PlantingSiteId) : SuccessResponsePayload

data class GetPlantingSiteResponsePayload(val site: PlantingSitePayload) : SuccessResponsePayload

data class ListPlantingSitesResponsePayload(val sites: List<PlantingSitePayload>) :
    SuccessResponsePayload

data class UpdatePlantingSiteRequestPayload(
    val description: String? = null,
    val name: String,
)
