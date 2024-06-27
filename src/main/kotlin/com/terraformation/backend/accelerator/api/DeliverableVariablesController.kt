package com.terraformation.backend.accelerator.api

import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.documentproducer.db.VariableStore
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/deliverables")
@RestController
class DeliverableVariablesController(private val variableStore: VariableStore) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{deliverableId}/variables")
  @Operation(summary = "Gets the list of variable IDs that belong to a deliverable.")
  fun getDeliverableVariables(
      @PathVariable deliverableId: DeliverableId
  ): GetDeliverableVariablesResponsePayload {
    return GetDeliverableVariablesResponsePayload(
        variableStore.fetchDeliverableVariableIds(deliverableId = deliverableId))
  }
}

data class GetDeliverableVariablesResponsePayload(val variableIds: List<VariableId>) :
    SuccessResponsePayload
