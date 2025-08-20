package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.event.FacilityAlertRequestedEvent
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.NewFacilityModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.log.perClassLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/facilities")
class FacilitiesController(
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
    val facility = facilityStore.fetchOneById(facilityId)

    return GetFacilityResponse(FacilityPayload(facility))
  }

  @Operation(summary = "Creates a new facility.")
  @PostMapping
  fun createFacility(
      @RequestBody payload: CreateFacilityRequestPayload
  ): CreateFacilityResponsePayload {
    val model = facilityStore.create(payload.toModel())

    return CreateFacilityResponsePayload(model.id)
  }

  @PutMapping("/{facilityId}")
  @Operation(summary = "Updates information about a facility.")
  fun updateFacility(
      @PathVariable facilityId: FacilityId,
      @RequestBody payload: UpdateFacilityRequestPayload,
  ): SimpleSuccessResponsePayload {
    val facility = facilityStore.fetchOneById(facilityId)

    facilityStore.update(
        facility.copy(
            buildCompletedDate = payload.buildCompletedDate,
            buildStartedDate = payload.buildStartedDate,
            capacity = payload.capacity,
            description = payload.description?.ifEmpty { null },
            name = payload.name,
            operationStartedDate = payload.operationStartedDate,
            timeZone = payload.timeZone,
        )
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse(
      responseCode = "202",
      description =
          "The request was received, but the user is still configuring or placing sensors, so no " +
              "notification has been generated.",
  )
  @PostMapping("/{facilityId}/alert/send")
  @Operation(summary = "Sends an alert to the facility's configured alert recipients.")
  fun sendFacilityAlert(
      @PathVariable facilityId: FacilityId,
      @RequestBody payload: SendFacilityAlertRequestPayload,
  ): ResponseEntity<SimpleSuccessResponsePayload> {
    requirePermissions { sendAlert(facilityId) }

    val connectionState = facilityStore.fetchOneById(facilityId).connectionState
    if (connectionState != FacilityConnectionState.Configured) {
      log.warn("Dropping alert from facility $facilityId with connection state $connectionState")
      log.info("Subject ${payload.subject}")
      log.info("Body ${payload.body}")
      return ResponseEntity.accepted().body(SimpleSuccessResponsePayload())
    }

    try {
      publisher.publishEvent(
          FacilityAlertRequestedEvent(
              facilityId,
              payload.subject,
              payload.body,
              currentUser().userId,
          )
      )
    } catch (e: Exception) {
      log.error("Unable to send alert email", e)
      throw InternalServerErrorException("Unable to send email message.")
    }

    return ResponseEntity.ok(SimpleSuccessResponsePayload())
  }

  @ApiResponse409(
      description = "The facility's device manager was not in the process of being configured."
  )
  @ApiResponseSimpleSuccess
  @Operation(
      summary = "Marks a facility as fully configured.",
      description =
          "After connecting a device manager and finishing any necessary configuration of the " +
              "facility's devices, send this request to enable processing of timeseries values " +
              "and alerts from the device manager. Only valid if the facility's connection " +
              "state is `Connected`.",
  )
  @PostMapping("/{facilityId}/configured")
  fun postConfigured(@PathVariable facilityId: FacilityId): SimpleSuccessResponsePayload {
    try {
      facilityStore.updateConnectionState(
          facilityId,
          FacilityConnectionState.Connected,
          FacilityConnectionState.Configured,
      )
      return SimpleSuccessResponsePayload()
    } catch (e: IllegalStateException) {
      throw WebApplicationException(
          "Facility's devices are not being configured.",
          Response.Status.CONFLICT,
      )
    }
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FacilityPayload(
    val buildCompletedDate: LocalDate?,
    val buildStartedDate: LocalDate?,
    @Schema(
        description =
            "For nursery facilities, the number of plants this nursery is capable of holding."
    )
    val capacity: Int?,
    val connectionState: FacilityConnectionState,
    val createdTime: Instant,
    val description: String?,
    @Schema(
        description =
            "Short numeric identifier for this facility. Facility numbers start at 1 for each " +
                "facility type in an organization."
    )
    val facilityNumber: Int,
    val id: FacilityId,
    val name: String,
    val operationStartedDate: LocalDate?,
    val organizationId: OrganizationId,
    val timeZone: ZoneId?,
    val type: FacilityType,
) {
  constructor(
      model: FacilityModel
  ) : this(
      model.buildCompletedDate,
      model.buildStartedDate,
      model.capacity,
      model.connectionState,
      model.createdTime.truncatedTo(ChronoUnit.SECONDS),
      model.description,
      model.facilityNumber,
      model.id,
      model.name,
      model.operationStartedDate,
      model.organizationId,
      model.timeZone,
      model.type,
  )
}

data class CreateFacilityRequestPayload(
    val buildCompletedDate: LocalDate?,
    val buildStartedDate: LocalDate?,
    @Schema(
        description =
            "For nursery facilities, the number of plants this nursery is capable of holding."
    )
    val capacity: Int?,
    val description: String?,
    val name: String,
    val operationStartedDate: LocalDate?,
    @Schema(description = "Which organization this facility belongs to.")
    val organizationId: OrganizationId,
    @ArraySchema(
        schema =
            Schema(
                description =
                    "The list of sub-locations to create. If this is absent or null, the system " +
                        "will create a default set of sub-locations if the facility is a seed " +
                        "bank. If it is an empty list, the seed bank will not have any " +
                        "sub-locations."
            )
    )
    val subLocationNames: Set<String>?,
    val timeZone: ZoneId?,
    val type: FacilityType,
) {
  fun toModel() =
      NewFacilityModel(
          buildCompletedDate = buildCompletedDate,
          buildStartedDate = buildStartedDate,
          capacity = capacity,
          description = description,
          name = name,
          operationStartedDate = operationStartedDate,
          organizationId = organizationId,
          subLocationNames = subLocationNames,
          timeZone = timeZone,
          type = type,
      )
}

data class CreateFacilityResponsePayload(val id: FacilityId) : SuccessResponsePayload

data class GetFacilityResponse(val facility: FacilityPayload) : SuccessResponsePayload

data class ListFacilitiesResponse(val facilities: List<FacilityPayload>) : SuccessResponsePayload

data class SendFacilityAlertRequestPayload(
    val subject: String,
    @Schema(description = "Alert body in plain text. HTML alerts are not supported yet.")
    val body: String,
)

data class UpdateFacilityRequestPayload(
    val buildCompletedDate: LocalDate?,
    val buildStartedDate: LocalDate?,
    @Schema(
        description =
            "For nursery facilities, the number of plants this nursery is capable of holding."
    )
    val capacity: Int?,
    val description: String?,
    val name: String,
    val operationStartedDate: LocalDate?,
    val timeZone: ZoneId?,
)
