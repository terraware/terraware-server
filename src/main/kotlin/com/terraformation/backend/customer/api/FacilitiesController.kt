package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.event.FacilityAlertRequestedEvent
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.AutomationNotFoundException
import com.terraformation.backend.db.FacilityConnectionState
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.log.perClassLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.ws.rs.InternalServerErrorException
import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.Response
import org.springframework.context.ApplicationEventPublisher
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/facility", "/api/v1/facilities")
class FacilitiesController(
    private val automationStore: AutomationStore,
    private val facilityStore: FacilityStore,
    private val publisher: ApplicationEventPublisher,
) {
  private val log = perClassLogger()

  @GetMapping
  @Operation(summary = "Lists all accessible facilities.")
  fun listAllFacilities(): ListFacilitiesResponse {
    val facilities = facilityStore.fetchAll()

    val elements = facilities.map { FacilityPayload(it) }
    return ListFacilitiesResponse(elements)
  }

  @GetMapping("/{facilityId}")
  @Operation(summary = "Gets information about a single facility.")
  fun getFacility(@PathVariable facilityId: FacilityId): GetFacilityResponse {
    val facility =
        facilityStore.fetchById(facilityId) ?: throw FacilityNotFoundException(facilityId)

    return GetFacilityResponse(FacilityPayload(facility))
  }

  @Operation(summary = "Creates a new facility.")
  @PostMapping
  fun createFacility(
      @RequestBody payload: CreateFacilityRequestPayload
  ): CreateFacilityResponsePayload {
    val model =
        facilityStore.create(payload.siteId, payload.type, payload.name, payload.description)
    return CreateFacilityResponsePayload(model.id)
  }

  @PutMapping("/{facilityId}")
  @Operation(summary = "Updates information about a facility.")
  fun updateFacility(
      @PathVariable facilityId: FacilityId,
      @RequestBody payload: UpdateFacilityRequestPayload
  ): SimpleSuccessResponsePayload {
    val facility =
        facilityStore.fetchById(facilityId) ?: throw FacilityNotFoundException(facilityId)

    facilityStore.update(
        facility.copy(name = payload.name, description = payload.description?.ifEmpty { null }))

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse(responseCode = "200", description = "Success")
  @ApiResponse404("The facility does not exist or is not accessible.")
  @GetMapping("/{facilityId}/automations")
  @Operation(summary = "Lists a facility's automations.")
  fun listAutomations(@PathVariable facilityId: FacilityId): ListAutomationsResponsePayload {
    val automations = automationStore.fetchByFacilityId(facilityId)

    return ListAutomationsResponsePayload(automations.map { AutomationPayload(it) })
  }

  @ApiResponse(responseCode = "200", description = "Success")
  @ApiResponse404("The facility does not exist or is not accessible.")
  @PostMapping("/{facilityId}/automations")
  @Operation(summary = "Creates a new automation at a facility.")
  fun createAutomation(
      @PathVariable facilityId: FacilityId,
      @RequestBody payload: ModifyAutomationRequestPayload
  ): CreateAutomationResponsePayload {
    val automationId =
        automationStore.create(facilityId, payload.name, payload.description, payload.configuration)
    return CreateAutomationResponsePayload(automationId)
  }

  @ApiResponse(responseCode = "200", description = "Success")
  @ApiResponse404
  @GetMapping("/{facilityId}/automations/{automationId}")
  @Operation(summary = "Gets information about a single automation.")
  fun getAutomation(
      @PathVariable facilityId: FacilityId,
      @PathVariable automationId: AutomationId
  ): GetAutomationResponsePayload {
    val model = automationStore.fetchOneById(automationId)
    if (model.facilityId != facilityId) {
      throw AutomationNotFoundException(automationId)
    }

    return GetAutomationResponsePayload(AutomationPayload(model))
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @PutMapping("/{facilityId}/automations/{automationId}")
  @Operation(summary = "Updates an existing automation.")
  fun updateAutomation(
      @PathVariable facilityId: FacilityId,
      @PathVariable automationId: AutomationId,
      @RequestBody payload: ModifyAutomationRequestPayload
  ): SimpleSuccessResponsePayload {
    val model = automationStore.fetchOneById(automationId)
    if (model.facilityId != facilityId) {
      throw AutomationNotFoundException(automationId)
    }

    automationStore.update(
        model.copy(
            configuration = payload.configuration,
            description = payload.description,
            name = payload.name))

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @DeleteMapping("/{facilityId}/automations/{automationId}")
  @Operation(summary = "Deletes an automation from a facility.")
  fun deleteAutomation(
      @PathVariable facilityId: FacilityId,
      @PathVariable automationId: AutomationId
  ): SimpleSuccessResponsePayload {
    if (facilityId != automationStore.fetchOneById(automationId).facilityId) {
      throw AutomationNotFoundException(automationId)
    }

    automationStore.delete(automationId)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @PostMapping("/{facilityId}/alert/send")
  @Operation(summary = "Sends an alert to the facility's configured alert recipients.")
  fun sendFacilityAlert(
      @PathVariable facilityId: FacilityId,
      @RequestBody payload: SendFacilityAlertRequestPayload
  ): SimpleSuccessResponsePayload {
    requirePermissions { sendAlert(facilityId) }

    try {
      publisher.publishEvent(
          FacilityAlertRequestedEvent(
              facilityId, payload.subject, payload.body, currentUser().userId))
    } catch (e: Exception) {
      log.error("Unable to send alert email", e)
      throw InternalServerErrorException("Unable to send email message.")
    }

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse409(
      description = "The facility's device manager was not in the process of being configured.")
  @ApiResponseSimpleSuccess
  @Operation(
      summary = "Marks a facility as fully configured.",
      description =
          "After connecting a device manager and finishing any necessary configuration of the " +
              "facility's devices, send this request to enable processing of timeseries values " +
              "and alerts from the device manager. Only valid if the facility's connection " +
              "state is `Connected`.")
  @PostMapping("/{facilityId}/configured")
  fun postConfigured(@PathVariable facilityId: FacilityId): SimpleSuccessResponsePayload {
    try {
      facilityStore.updateConnectionState(
          facilityId, FacilityConnectionState.Connected, FacilityConnectionState.Configured)
      return SimpleSuccessResponsePayload()
    } catch (e: IllegalStateException) {
      throw WebApplicationException(
          "Facility's devices are not being configured.", Response.Status.CONFLICT)
    }
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AutomationPayload(
    val id: AutomationId,
    val facilityId: FacilityId,
    @Schema(
        description = "Short human-readable name of this automation.",
    )
    val name: String,
    @Schema(description = "Human-readable description of this automation.")
    val description: String?,
    @Schema(description = "Client-defined configuration data for this automation.")
    val configuration: Map<String, Any?>?,
) {
  constructor(
      model: AutomationModel
  ) : this(model.id, model.facilityId, model.name, model.description, model.configuration)
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FacilityPayload(
    val connectionState: FacilityConnectionState,
    val createdTime: Instant,
    val description: String?,
    val id: FacilityId,
    val siteId: SiteId,
    val name: String,
    val type: FacilityType,
) {
  constructor(
      model: FacilityModel
  ) : this(
      model.connectionState,
      model.createdTime.truncatedTo(ChronoUnit.SECONDS),
      model.description,
      model.id,
      model.siteId,
      model.name,
      model.type)
}

data class ModifyAutomationRequestPayload(
    val name: String,
    val description: String?,
    val configuration: Map<String, Any?>?,
)

data class CreateAutomationResponsePayload(val id: AutomationId) : SuccessResponsePayload

data class CreateFacilityRequestPayload(
    val description: String?,
    val name: String,
    val type: FacilityType,
    val siteId: SiteId,
)

data class CreateFacilityResponsePayload(val id: FacilityId) : SuccessResponsePayload

data class GetAutomationResponsePayload(val automation: AutomationPayload) : SuccessResponsePayload

data class GetFacilityResponse(val facility: FacilityPayload) : SuccessResponsePayload

data class ListAutomationsResponsePayload(val automations: List<AutomationPayload>) :
    SuccessResponsePayload

data class ListFacilitiesResponse(val facilities: List<FacilityPayload>) : SuccessResponsePayload

data class SendFacilityAlertRequestPayload(
    val subject: String,
    @Schema(description = "Alert body in plain text. HTML alerts are not supported yet.")
    val body: String
)

data class UpdateFacilityRequestPayload(val description: String?, val name: String)
