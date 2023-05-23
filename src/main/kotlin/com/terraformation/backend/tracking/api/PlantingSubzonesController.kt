package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.model.ExistingSpeciesModel
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/subzones")
@RestController
@TrackingEndpoint
class PlantingSubzonesController(private val speciesStore: SpeciesStore) {
  @GetMapping("/{id}/species")
  fun listPlantingSubzoneSpecies(
      @PathVariable id: PlantingSubzoneId,
  ): ListPlantingSubzoneSpeciesResponsePayload {
    val species = speciesStore.fetchSpeciesByPlantingSubzoneId(id)

    return ListPlantingSubzoneSpeciesResponsePayload(
        species.map { PlantingSubzoneSpeciesPayload(it) })
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
