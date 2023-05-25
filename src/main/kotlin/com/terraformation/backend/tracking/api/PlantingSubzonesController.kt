package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonesRow
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.tracking.db.PlantingSiteStore
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
    private val plantingSiteStore: PlantingSiteStore,
    private val speciesStore: SpeciesStore,
) {
  @GetMapping("/{id}/species")
  fun listPlantingSubzoneSpecies(
      @PathVariable id: PlantingSubzoneId,
  ): ListPlantingSubzoneSpeciesResponsePayload {
    val species = speciesStore.fetchSpeciesByPlantingSubzoneId(id)

    return ListPlantingSubzoneSpeciesResponsePayload(
        species.map { PlantingSubzoneSpeciesPayload(it) })
  }

  @PutMapping("/{id}")
  fun updatePlantingSubzone(
      @PathVariable id: PlantingSubzoneId,
      @RequestBody payload: UpdatePlantingSubzoneRequestPayload
  ): SimpleSuccessResponsePayload {
    plantingSiteStore.updatePlantingSubzone(id) { payload.applyTo(it) }

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
    val fullyPlanted: Boolean,
) {
  fun applyTo(row: PlantingSubzonesRow) = row.copy(fullyPlanted = fullyPlanted)
}
