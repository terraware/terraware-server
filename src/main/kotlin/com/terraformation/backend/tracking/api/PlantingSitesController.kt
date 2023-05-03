package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.species.api.SpeciesResponseElement
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.Explode
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
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
    private val speciesStore: SpeciesStore,
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
    val includeZones = full ?: false
    val models = plantingSiteStore.fetchSitesByOrganizationId(organizationId, includeZones)
    val payloads = models.map { PlantingSitePayload(it) }
    return ListPlantingSitesResponsePayload(payloads)
  }

  @GetMapping("/{id}")
  fun getPlantingSite(
      @PathVariable("id") id: PlantingSiteId,
  ): GetPlantingSiteResponsePayload {
    val model = plantingSiteStore.fetchSiteById(id, includeSubzones = true)
    return GetPlantingSiteResponsePayload(PlantingSitePayload(model))
  }

  @PostMapping
  fun createPlantingSite(
      @RequestBody payload: CreatePlantingSiteRequestPayload
  ): CreatePlantingSiteResponsePayload {
    val model =
        plantingSiteStore.createPlantingSite(
            payload.organizationId, payload.name, payload.description, payload.timeZone)
    return CreatePlantingSiteResponsePayload(model.id)
  }

  @PutMapping("/{id}")
  fun updatePlantingSite(
      @PathVariable("id") id: PlantingSiteId,
      @RequestBody payload: UpdatePlantingSiteRequestPayload
  ): SimpleSuccessResponsePayload {
    plantingSiteStore.updatePlantingSite(id, payload.name, payload.description, payload.timeZone)
    return SimpleSuccessResponsePayload()
  }

  @GetMapping("/{id}/species")
  @Operation(summary = "Gets a list of the species that have been planted at a planting site.")
  fun listPlantingSiteSpecies(
      @PathVariable("id") //
      id: PlantingSiteId,
      @Parameter(
          explode = Explode.TRUE,
          array =
              ArraySchema(
                  schema =
                      Schema(
                          description =
                              "Limit results to species in specified planting subzones.")))
      @RequestParam
      plantingSubzoneId: List<PlantingSubzoneId>? = null,
  ): ListPlantingSiteSpeciesResponsePayload {
    val speciesByZone = speciesStore.fetchSpeciesByPlantingSubzoneIds(id, plantingSubzoneId)

    return ListPlantingSiteSpeciesResponsePayload(speciesByZone)
  }
}

data class PlantingSubzonePayload(
    val boundary: MultiPolygon,
    val fullName: String,
    val id: PlantingSubzoneId,
    val name: String,
) {
  constructor(
      model: PlantingSubzoneModel
  ) : this(
      model.boundary,
      model.fullName,
      model.id,
      model.name,
  )
}

data class PlantingZonePayload(
    val boundary: MultiPolygon,
    val id: PlantingZoneId,
    val name: String,
    val plantingSubzones: List<PlantingSubzonePayload>,
) {
  constructor(
      model: PlantingZoneModel
  ) : this(
      model.boundary,
      model.id,
      model.name,
      model.plantingSubzones.map { PlantingSubzonePayload(it) })
}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class PlantingSitePayload(
    val boundary: MultiPolygon?,
    val description: String?,
    val id: PlantingSiteId,
    val name: String,
    val plantingZones: List<PlantingZonePayload>?,
    val timeZone: ZoneId?,
) {
  constructor(
      model: PlantingSiteModel
  ) : this(
      model.boundary,
      model.description,
      model.id,
      model.name,
      model.plantingZones.map { PlantingZonePayload(it) },
      model.timeZone,
  )
}

data class PlantingSubzoneSpeciesElement(
    val plantingSubzoneId: PlantingSubzoneId,
    val species: List<SpeciesResponseElement>,
)

data class CreatePlantingSiteRequestPayload(
    val description: String? = null,
    val name: String,
    val organizationId: OrganizationId,
    val timeZone: ZoneId?,
)

data class CreatePlantingSiteResponsePayload(val id: PlantingSiteId) : SuccessResponsePayload

data class GetPlantingSiteResponsePayload(val site: PlantingSitePayload) : SuccessResponsePayload

data class ListPlantingSiteSpeciesResponsePayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "List of species for each planting subzone. A species may appear in more " +
                        "than one planting subzone."))
    val plantingSubzones: List<PlantingSubzoneSpeciesElement>,
) : SuccessResponsePayload {
  constructor(
      speciesBySubzone: Map<PlantingSubzoneId, List<ExistingSpeciesModel>>
  ) : this(
      speciesBySubzone.map { (plantingSubzoneId, speciesModels) ->
        PlantingSubzoneSpeciesElement(
            plantingSubzoneId,
            speciesModels.map { speciesModel -> SpeciesResponseElement(speciesModel, null) })
      })
}

data class ListPlantingSitesResponsePayload(val sites: List<PlantingSitePayload>) :
    SuccessResponsePayload

data class UpdatePlantingSiteRequestPayload(
    val description: String? = null,
    val name: String,
    val timeZone: ZoneId?,
)
