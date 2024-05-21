package com.terraformation.backend.support.api

import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.support.SupportService
import com.terraformation.backend.support.atlassian.model.ServiceRequestTypeModel
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/support")
@RestController
class SupportController(private val service: SupportService) {
  @GetMapping
  @Operation(summary = "Lists support request types.")
  fun listRequestTypes(): ListSupportRequestTypesResponsePayload {
    return ListSupportRequestTypesResponsePayload(service.listServiceRequestTypes())
  }

  @PostMapping
  @Operation(summary = "Submit support request types.")
  fun submitRequest(
      @RequestBody payload: SubmitSupportRequestPayload
  ): SimpleSuccessResponsePayload {
    service.submitServiceRequest(payload.description, payload.summary, payload.id)
    return SimpleSuccessResponsePayload()
  }
}

data class ListSupportRequestTypesResponsePayload(val types: List<ServiceRequestTypeModel>)

data class SubmitSupportRequestPayload(
    val id: Int,
    val description: String,
    val summary: String,
)
