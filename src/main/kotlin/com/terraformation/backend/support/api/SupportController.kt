package com.terraformation.backend.support.api

import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.SupportEndpoint
import com.terraformation.backend.support.SupportService
import com.terraformation.backend.support.atlassian.model.ServiceRequestTypeModel
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
        service.listServiceRequestTypes().map { ServiceRequestType(it) })
  }

  @ApiResponse(responseCode = "200")
  @PostMapping
  @Operation(summary = "Submit support request types.")
  fun submitRequest(
      @RequestBody payload: SubmitSupportRequestPayload
  ): SubmitSupportRequestResponsePayload {
    val issueKey =
        service.submitServiceRequest(payload.description, payload.summary, payload.requestTypeId)
    return SubmitSupportRequestResponsePayload(issueKey)
  }
}

data class ServiceRequestType(
    val requestTypeId: Int,
    val name: String,
    val description: String,
) {
  constructor(
      model: ServiceRequestTypeModel
  ) : this(
      requestTypeId = model.id,
      name = model.name,
      model.description,
  )
}

data class ListSupportRequestTypesResponsePayload(val types: List<ServiceRequestType>) :
    SuccessResponsePayload

data class SubmitSupportRequestPayload(
    val requestTypeId: Int,
    val description: String,
    val summary: String,
)

data class SubmitSupportRequestResponsePayload(val issueKey: String) : SuccessResponsePayload
