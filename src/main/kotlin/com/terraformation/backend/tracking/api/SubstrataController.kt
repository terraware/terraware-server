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
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking")
@RestController
@TrackingEndpoint
class SubstrataController(
    private val parentStore: ParentStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val speciesStore: SpeciesStore,
) {
  @GetMapping("/subzones/{id}/species")
  @Operation(
      summary = "Gets a list of the species that may have been planted in a planting subzone.",
      description =
          "The list is based on nursery withdrawals to the planting site as well as past " +
              "observations.",
  )
  fun listSubstratumSpecies(
      @PathVariable id: PlantingSubzoneId,
  ): ListSubstratumSpeciesResponsePayload {
    val species = speciesStore.fetchSiteSpeciesByPlantingSubzoneId(id)

    return ListSubstratumSpeciesResponsePayload(species.map { SubstratumSpeciesPayload(it) })
  }

  @Operation(
      summary = "Updates the planting-completed state of a substratum.",
      description = "Deprecated, use /substrata/{id} instead",
      deprecated = true,
  )
  @PutMapping("/subzones/{id}")
  fun updatePlantingSubzone(
      @PathVariable id: PlantingSubzoneId,
      @RequestBody payload: UpdatePlantingSubzoneRequestPayload,
  ): SimpleSuccessResponsePayload {
    plantingSiteStore.updatePlantingSubzoneCompleted(id, payload.plantingCompleted)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Updates the planting-completed state of a substratum.")
  @PutMapping("/substrata/{id}")
  fun updateSubstrata(
      @PathVariable id: PlantingSubzoneId,
      @RequestBody payload: UpdateSubstratumRequestPayload,
  ): SimpleSuccessResponsePayload {
    plantingSiteStore.updatePlantingSubzoneCompleted(id, payload.plantingCompleted)

    return SimpleSuccessResponsePayload()
  }
}

sealed interface SpeciesPayload

@Schema(description = "Use SubstratumSpeciesPayload instead", deprecated = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class PlantingSubzoneSpeciesPayload(
    val commonName: String?,
    val id: SpeciesId,
    val scientificName: String,
) : SpeciesPayload

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class SubstratumSpeciesPayload(
    val commonName: String?,
    val id: SpeciesId,
    val scientificName: String,
) : SpeciesPayload {
  constructor(model: ExistingSpeciesModel) : this(model.commonName, model.id, model.scientificName)
}

data class ListSubstratumSpeciesResponsePayload(
    @ArraySchema(
        arraySchema =
            Schema(oneOf = [PlantingSubzoneSpeciesPayload::class, SubstratumSpeciesPayload::class])
    )
    val species: List<SpeciesPayload>
) : SuccessResponsePayload

sealed interface UpdateSubstratumRequestPayloadProps {
  val plantingCompleted: Boolean
}

data class UpdateSubstratumRequestPayload(
    override val plantingCompleted: Boolean,
) : UpdateSubstratumRequestPayloadProps

data class UpdatePlantingSubzoneRequestPayload(
    override val plantingCompleted: Boolean,
) : UpdateSubstratumRequestPayloadProps
