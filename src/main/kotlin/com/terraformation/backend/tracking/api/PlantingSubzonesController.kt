package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.tracking.db.PlantingSiteStore
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/subzones")
@RestController
@TrackingEndpoint
class PlantingSubzonesController(
    private val parentStore: ParentStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val speciesStore: SpeciesStore,
) {
  @GetMapping("/{id}/species")
  @Operation(
      summary = "Gets a list of the species that may have been planted in a planting subzone.",
      description =
          "The list is based on nursery withdrawals to the planting site as well as past " +
              "observations.",
  )
  fun listPlantingSubzoneSpecies(
      @PathVariable id: PlantingSubzoneId,
  ): ListPlantingSubzoneSpeciesResponsePayload {
    val species = speciesStore.fetchSiteSpeciesByPlantingSubzoneId(id)

    return ListPlantingSubzoneSpeciesResponsePayload(
        species.map { PlantingSubzoneSpeciesPayload(it) }
    )
  }

  @Operation(summary = "Updates the planting-completed state of a planting subzone.")
  @PutMapping("/{id}")
  fun updatePlantingSubzone(
      @PathVariable id: PlantingSubzoneId,
      @RequestBody payload: UpdatePlantingSubzoneRequestPayload,
  ): SimpleSuccessResponsePayload {
    plantingSiteStore.updatePlantingSubzoneCompleted(id, payload.plantingCompleted)

    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class PlantingSubzoneSpeciesPayload(
    val commonName: String?,
    val id: SpeciesId,
    val scientificName: String,
) {
  constructor(model: ExistingSpeciesModel) : this(model.commonName, model.id, model.scientificName)
}

data class ListPlantingSubzoneSpeciesResponsePayload(
    val species: List<PlantingSubzoneSpeciesPayload>
) : SuccessResponsePayload

data class UpdatePlantingSubzoneRequestPayload(
    val plantingCompleted: Boolean,
)
