package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlotId
import com.terraformation.backend.tracking.db.DeliveryStore
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.constraints.Min
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
  @PostMapping("/{id}/reassign")
  fun reassignDelivery(
      @PathVariable("id") deliveryId: DeliveryId,
      @RequestBody payload: ReassignDeliveryRequestPayload
  ): SimpleSuccessResponsePayload {
    deliveryStore.reassignDelivery(deliveryId, payload.reassignments.map { it.toModel() })
    return SimpleSuccessResponsePayload()
  }
}

data class ReassignmentPayload(
    val fromPlantingId: PlantingId,
    @JsonSetter(nulls = Nulls.FAIL)
    @Min(1)
    @Schema(
        description =
            "Number of plants to reassign from the planting's original plot to the new one. " +
                "Must be less than or equal to the number of plants in the original planting.")
    val numPlants: Int,
    val notes: String?,
    val toPlotId: PlotId,
) {
  fun toModel() = DeliveryStore.Reassignment(fromPlantingId, numPlants, notes, toPlotId)
}

data class ReassignDeliveryRequestPayload(
    val reassignments: List<ReassignmentPayload>,
)
