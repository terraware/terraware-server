package com.terraformation.seedbank.api.rhizo

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.seedbank.api.SuccessResponsePayload
import com.terraformation.seedbank.api.annotation.DeviceManagerAppEndpoint
import com.terraformation.seedbank.auth.ClientIdentity
import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.DeviceStore
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@DeviceManagerAppEndpoint
@RestController
@RequestMapping("/api/v1/device")
class DeviceController(
    private val config: TerrawareServerConfig,
    private val deviceStore: DeviceStore,
) {
  @GetMapping("/all/config")
  fun listDeviceConfigs(@AuthenticationPrincipal auth: ClientIdentity): ListDeviceConfigsResponse {
    val devices = deviceStore.fetchDeviceConfigurationForSite(config.siteModuleId)
    return ListDeviceConfigsResponse(devices)
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DeviceConfig(
    @Schema(description = "Name of site module where this device is located.", example = "garage")
    val siteModule: String,
    @Schema(
        description = "Name of this device. Should be unique within the site module.",
        example = "BMU-1")
    val name: String,
    @Schema(
        description =
            "High-level type of the device. Device manager may use this in conjunction " +
                "with the make and model to determine which metrics to report.",
        example = "inverter")
    val type: String,
    @Schema(description = "Name of device manufacturer.", example = "InHand Networks")
    val make: String,
    @Schema(description = "Model number or model name of the device.", example = "IR915L")
    val model: String,
    @Schema(description = "Device manager protocol name.", example = "modbus")
    val protocol: String?,
    @Schema(
        description =
            "Protocol-specific address of device, e.g., an IP address or a Bluetooth device ID.",
        example = "192.168.1.100")
    val address: String?,
    @Schema(description = "Port number if relevant for the protocol.", example = "50000")
    val port: Int?,
    @Schema(
        description =
            "Protocol- and device-specific custom settings. Format is defined by the device manager.")
    val settings: String?,
    val pollingInterval: Int?,
) {
  @get:Schema(
      description =
          "Device's resource path on the server, minus organization and site names. Currently, " +
              "this is always just the site module and device name.",
      example = "garage/BMU-1")
  @Suppress("unused")
  val serverPath: String
    get() = "$siteModule/$name"
}

data class ListDeviceConfigsResponse(val devices: List<DeviceConfig>) : SuccessResponsePayload
