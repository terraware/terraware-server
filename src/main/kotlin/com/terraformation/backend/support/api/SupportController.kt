package com.terraformation.backend.support.api

import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SupportEndpoint
import com.terraformation.backend.support.SupportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/support")
@RestController
@SupportEndpoint
class SupportController(private val service: SupportService) {

  @ApiResponse(responseCode = "200")
  @GetMapping
  @Operation(summary = "Lists support request types.")
  fun listRequestTypes(): ListSupportRequestTypesResponsePayload {
    return ListSupportRequestTypesResponsePayload(
        service.listServiceRequestTypes().map {
          ServiceRequestType(it.id, it.name, it.description)
        })
  }

  @ApiResponse(responseCode = "200")
  @PostMapping
  @Operation(summary = "Submit support request types.")
  fun submitRequest(
      @RequestBody payload: SubmitSupportRequestPayload
  ): SimpleSuccessResponsePayload {
    service.submitServiceRequest(payload.description, payload.summary, payload.requestTypeId)
    return SimpleSuccessResponsePayload()
  }
}

data class ServiceRequestType(
    val requestTypeId: Int,
    val name: String,
    val description: String,
)

data class ListSupportRequestTypesResponsePayload(val types: List<ServiceRequestType>)

data class SubmitSupportRequestPayload(
    val requestTypeId: Int,
    val description: String,
    val summary: String,
)
