package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.tracking.db.DeliveryStore
import com.terraformation.backend.tracking.model.DeliveryModel
import com.terraformation.backend.tracking.model.PlantingModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/deliveries")
@RestController
@TrackingEndpoint
class DeliveriesController(
    private val deliveryStore: DeliveryStore,
) {
  @GetMapping("/{id}")
  @Operation(
      summary = "Gets information about a specific delivery of seedlings to a planting site."
  )
  fun getDelivery(@PathVariable("id") deliveryId: DeliveryId): GetDeliveryResponsePayload {
    val model = deliveryStore.fetchOneById(deliveryId)
    return GetDeliveryResponsePayload(DeliveryPayload(model))
  }

  @Operation(
      summary = "Reassigns some of the seedlings from a delivery to a different planting subzone."
  )
  @PostMapping("/{id}/reassign")
  fun reassignDelivery(
      @PathVariable("id") deliveryId: DeliveryId,
      @RequestBody payload: ReassignDeliveryRequestPayload,
  ): SimpleSuccessResponsePayload {
    deliveryStore.reassignDelivery(deliveryId, payload.reassignments.map { it.toModel() })
    return SimpleSuccessResponsePayload()
  }
}

data class PlantingPayload(
    val id: PlantingId,
    @Schema(description = "If type is \"Reassignment To\", the reassignment notes, if any.")
    val notes: String?,
    @Schema(
        description =
            "Number of plants planted or reassigned. If type is \"Reassignment From\", this " +
                "will be negative."
    )
    val numPlants: Int,
    val plantingSubzoneId: PlantingSubzoneId?,
    val speciesId: SpeciesId,
    val type: PlantingType,
) {
  constructor(
      model: PlantingModel
  ) : this(
      id = model.id,
      notes = model.notes,
      numPlants = model.numPlants,
      plantingSubzoneId = model.plantingSubzoneId,
      speciesId = model.speciesId,
      type = model.type,
  )
}

data class DeliveryPayload(
    val id: DeliveryId,
    val plantings: List<PlantingPayload>,
    val plantingSiteId: PlantingSiteId,
    val withdrawalId: WithdrawalId,
) {
  constructor(
      model: DeliveryModel,
  ) : this(
      id = model.id,
      plantings = model.plantings.map { PlantingPayload(it) },
      plantingSiteId = model.plantingSiteId,
      withdrawalId = model.withdrawalId,
  )
}

data class ReassignmentPayload(
    val fromPlantingId: PlantingId,
    @JsonSetter(nulls = Nulls.FAIL)
    @Min(1)
    @Schema(
        description =
            "Number of plants to reassign from the planting's original subzone to the new one. " +
                "Must be less than or equal to the number of plants in the original planting."
    )
    val numPlants: Int,
    val notes: String?,
    val toPlantingSubzoneId: PlantingSubzoneId,
) {
  fun toModel() = DeliveryStore.Reassignment(fromPlantingId, numPlants, notes, toPlantingSubzoneId)
}

data class GetDeliveryResponsePayload(val delivery: DeliveryPayload) : SuccessResponsePayload

data class ReassignDeliveryRequestPayload(
    val reassignments: List<ReassignmentPayload>,
)
