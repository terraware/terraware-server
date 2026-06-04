package com.terraformation.backend.plantingmanagement.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.plantingmanagement.ExistingPlantingSeasonScheduledDateModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateSpecies
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDatesService
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonScheduledDatesStore
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import java.time.LocalDate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/planting-seasons/{plantingSeasonId}/scheduled-dates")
@RestController
@TrackingEndpoint
class PlantingSeasonScheduledDatesController(
    private val plantingSeasonScheduledDatesService: PlantingSeasonScheduledDatesService,
    private val plantingSeasonScheduledDatesStore: PlantingSeasonScheduledDatesStore,
) {
  @ApiResponse200
  @Operation(summary = "Gets all scheduled dates for a planting season.")
  @GetMapping
  fun getScheduledPlantingDates(
      @PathVariable plantingSeasonId: PlantingSeasonId,
  ): ListScheduledDatesResponsePayload {
    val models = plantingSeasonScheduledDatesStore.fetchList(plantingSeasonId)

    return ListScheduledDatesResponsePayload(
        scheduledDates = models.map { ScheduledDatePayload(it) }
    )
  }

  @ApiResponse200
  @Operation(summary = "Gets a single scheduled date for a planting season.")
  @GetMapping("/{scheduledPlantingDateId}")
  fun getSingleScheduledPlantingDate(
      @PathVariable plantingSeasonId: PlantingSeasonId,
      @PathVariable scheduledPlantingDateId: ScheduledPlantingDateId,
  ): GetScheduledDateResponsePayload {
    val model = plantingSeasonScheduledDatesStore.fetch(plantingSeasonId, scheduledPlantingDateId)

    return GetScheduledDateResponsePayload(scheduledDate = ScheduledDatePayload(model))
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @Operation(
      summary = "Creates a new scheduled date for a planting season.",
  )
  @PostMapping
  fun createScheduledPlantingDate(
      @PathVariable plantingSeasonId: PlantingSeasonId,
      @RequestBody @Valid payload: ScheduledPlantingDateRequestPayload,
  ): SimpleSuccessResponsePayload {
    plantingSeasonScheduledDatesService.create(
        payload.toModel(plantingSeasonId),
        payload.createNurseryRequest == true,
        payload.nurseryRequestNotes,
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @Operation(
      summary = "Updates a scheduled date for a planting season.",
  )
  @PutMapping("/{scheduledPlantingDateId}")
  fun updateScheduledPlantingDate(
      @PathVariable plantingSeasonId: PlantingSeasonId,
      @PathVariable scheduledPlantingDateId: ScheduledPlantingDateId,
      @RequestBody @Valid payload: ScheduledPlantingDateRequestPayload,
  ): SimpleSuccessResponsePayload {
    plantingSeasonScheduledDatesStore.update(
        scheduledPlantingDateId,
        payload.toModel(plantingSeasonId),
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse200
  @Operation(
      summary = "Deletes a scheduled date for a planting season.",
  )
  @DeleteMapping("/{scheduledPlantingDateId}")
  fun deleteScheduledPlantingDate(
      @PathVariable plantingSeasonId: PlantingSeasonId,
      @PathVariable scheduledPlantingDateId: ScheduledPlantingDateId,
  ): SimpleSuccessResponsePayload {
    plantingSeasonScheduledDatesStore.delete(plantingSeasonId, scheduledPlantingDateId)

    return SimpleSuccessResponsePayload()
  }
}

data class ScheduledPlantingDateSpeciesPayload(
    @field:Min(0) val quantity: Int,
    val speciesId: SpeciesId,
    val substratumId: SubstratumId,
) {
  fun toModel(): PlantingSeasonScheduledDateSpecies =
      PlantingSeasonScheduledDateSpecies(
          quantity = quantity,
          speciesId = speciesId,
          substratumId = substratumId,
      )
}

data class ScheduledPlantingDateRequestPayload(
    val createNurseryRequest: Boolean? = false,
    val date: LocalDate,
    val nurseryRequestNotes: String? = null,
    val species: List<ScheduledPlantingDateSpeciesPayload>,
) {
  fun toModel(plantingSeasonId: PlantingSeasonId): PlantingSeasonScheduledDateModel =
      PlantingSeasonScheduledDateModel(
          date = date,
          plantingSeasonId = plantingSeasonId,
          species = species.map { it.toModel() },
      )
}

data class ListScheduledDatesResponsePayload(val scheduledDates: List<ScheduledDatePayload>) :
    SuccessResponsePayload

data class GetScheduledDateResponsePayload(val scheduledDate: ScheduledDatePayload) :
    SuccessResponsePayload

data class ScheduledDatePayload(
    val date: LocalDate,
    val scheduledPlantingDateId: ScheduledPlantingDateId,
    val species: List<ScheduledPlantingDateSpeciesPayload>,
) {
  constructor(
      model: ExistingPlantingSeasonScheduledDateModel
  ) : this(
      date = model.date,
      scheduledPlantingDateId = model.scheduledPlantingDateId,
      species =
          model.species.map {
            ScheduledPlantingDateSpeciesPayload(
                quantity = it.quantity,
                speciesId = it.speciesId,
                substratumId = it.substratumId,
            )
          },
  )
}
