package com.terraformation.backend.device.api

import com.terraformation.backend.api.DeviceManagerAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.device.AutomationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@DeviceManagerAppEndpoint
@RequestMapping("/api/v1/automations")
@RestController
class AutomationsController(
    private val automationService: AutomationService,
) {
  @Operation(summary = "Reports that an automation has been triggered.")
  @PostMapping("/{automationId}/trigger")
  fun postAutomationTrigger(
      @PathVariable automationId: AutomationId,
      @RequestBody payload: AutomationTriggerRequestPayload
  ): SimpleSuccessResponsePayload {
    automationService.trigger(automationId, payload.timeseriesValue, payload.message)
    return SimpleSuccessResponsePayload()
  }
}

data class AutomationTriggerRequestPayload(
    @Schema(
        description =
            "For automations that are triggered by changes to timeseries values, the value that " +
                "triggered the automation.")
    val timeseriesValue: Double?,
    @Schema(
        description =
            "Default message to publish if the automation type isn't yet supported by the server.")
    val message: String?,
)
