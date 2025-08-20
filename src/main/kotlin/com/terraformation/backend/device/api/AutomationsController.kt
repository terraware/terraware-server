package com.terraformation.backend.device.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ArbitraryJsonObject
import com.terraformation.backend.api.DeviceManagerAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.device.AutomationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.ws.rs.BadRequestException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@DeviceManagerAppEndpoint
@RequestMapping("/api/v1/automations")
@RestController
class AutomationsController(
    private val automationService: AutomationService,
    private val automationStore: AutomationStore,
) {
  @GetMapping
  @Operation(summary = "Gets a list of automations for a device or facility.")
  fun listAutomations(
      @RequestParam deviceId: DeviceId?,
      @RequestParam facilityId: FacilityId?,
  ): ListAutomationsResponsePayload {
    val automations =
        if (facilityId != null && deviceId == null) {
          automationStore.fetchByFacilityId(facilityId)
        } else if (deviceId != null && facilityId == null) {
          automationStore.fetchByDeviceId(deviceId)
        } else {
          throw BadRequestException("deviceId or facilityId must be specified, but not both")
        }

    return ListAutomationsResponsePayload(automations.map { AutomationPayload(it) })
  }

  @GetMapping("/{automationId}")
  @Operation(summary = "Gets the details of a single automation for a device or facility.")
  fun getAutomation(
      @PathVariable("automationId") automationId: AutomationId
  ): GetAutomationResponsePayload {
    val automation = automationStore.fetchOneById(automationId)
    return GetAutomationResponsePayload(AutomationPayload(automation))
  }

  @Operation(summary = "Creates a new automation for a device or facility.")
  @PostMapping
  fun createAutomation(
      @RequestBody payload: CreateAutomationRequestPayload
  ): CreateAutomationResponsePayload {
    val automationId =
        automationStore.create(
            payload.facilityId,
            payload.type,
            payload.name,
            payload.description,
            payload.deviceId,
            payload.timeseriesName,
            payload.verbosity ?: 0,
            payload.lowerThreshold,
            payload.upperThreshold,
            payload.settings,
        )

    return CreateAutomationResponsePayload(automationId)
  }

  @Operation(summary = "Updates an existing automation for a device or facility.")
  @PutMapping("/{automationId}")
  fun updateAutomation(
      @PathVariable("automationId") automationId: AutomationId,
      @RequestBody payload: UpdateAutomationRequestPayload,
  ): SimpleSuccessResponsePayload {
    val existing = automationStore.fetchOneById(automationId)
    automationStore.update(payload.toModel(existing))
    return SimpleSuccessResponsePayload()
  }

  @DeleteMapping("/{automationId}")
  @Operation(summary = "Deletes an existing automation from a device or facility.")
  fun deleteAutomation(
      @PathVariable("automationId") automationId: AutomationId
  ): SimpleSuccessResponsePayload {
    automationStore.delete(automationId)
    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Reports that an automation has been triggered.")
  @PostMapping("/{automationId}/trigger")
  fun postAutomationTrigger(
      @PathVariable automationId: AutomationId,
      @RequestBody payload: AutomationTriggerRequestPayload,
  ): SimpleSuccessResponsePayload {
    automationService.trigger(automationId, payload.timeseriesValue, payload.message)
    return SimpleSuccessResponsePayload()
  }
}

data class AutomationTriggerRequestPayload(
    @Schema(
        description =
            "For automations that are triggered by changes to timeseries values, the value that " +
                "triggered the automation."
    )
    val timeseriesValue: Double?,
    @Schema(
        description =
            "Default message to publish if the automation type isn't yet supported by the server."
    )
    val message: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AutomationPayload(
    val id: AutomationId,
    val facilityId: FacilityId,
    val type: String,
    @Schema(
        description = "Short human-readable name of this automation.",
    )
    val name: String,
    @Schema(description = "Human-readable description of this automation.")
    val description: String?,
    val deviceId: DeviceId?,
    val timeseriesName: String?,
    val verbosity: Int,
    val lowerThreshold: Double?,
    val upperThreshold: Double?,
    @Schema(description = "Client-defined configuration data for this automation.")
    val settings: ArbitraryJsonObject?,
) {
  constructor(
      model: AutomationModel
  ) : this(
      model.id,
      model.facilityId,
      model.type,
      model.name,
      model.description,
      model.deviceId,
      model.timeseriesName,
      model.verbosity,
      model.lowerThreshold,
      model.upperThreshold,
      model.settings,
  )
}

data class CreateAutomationRequestPayload(
    val facilityId: FacilityId,
    val type: String,
    val name: String,
    val description: String?,
    val deviceId: DeviceId?,
    val timeseriesName: String?,
    val verbosity: Int?,
    val lowerThreshold: Double?,
    val upperThreshold: Double?,
    val settings: ArbitraryJsonObject?,
)

data class UpdateAutomationRequestPayload(
    val type: String,
    val name: String,
    val description: String?,
    val deviceId: DeviceId?,
    val timeseriesName: String?,
    val verbosity: Int?,
    val lowerThreshold: Double?,
    val upperThreshold: Double?,
    val settings: ArbitraryJsonObject?,
) {
  fun toModel(existing: AutomationModel): AutomationModel {
    return existing.copy(
        deviceId = deviceId,
        description = description,
        lowerThreshold = lowerThreshold,
        name = name,
        settings = settings,
        timeseriesName = timeseriesName,
        type = type,
        upperThreshold = upperThreshold,
        verbosity = verbosity ?: 0,
    )
  }
}

data class CreateAutomationResponsePayload(val id: AutomationId) : SuccessResponsePayload

data class GetAutomationResponsePayload(val automation: AutomationPayload) : SuccessResponsePayload

data class ListAutomationsResponsePayload(val automations: List<AutomationPayload>) :
    SuccessResponsePayload
