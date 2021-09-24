package com.terraformation.backend.device.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.DeviceManagerAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.pojos.DevicesRow
import com.terraformation.backend.device.db.DeviceStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@DeviceManagerAppEndpoint
@RestController
class DeviceController(
    private val deviceStore: DeviceStore,
    private val objectMapper: ObjectMapper
) {
  @ApiResponse(responseCode = "200", description = "Successfully listed the facility's devices.")
  @ApiResponse404(
      description = "The facility does not exist or is not accessible by the current user.")
  @GetMapping("/api/v1/facility/{facilityId}/devices")
  @Operation(summary = "Lists the configurations of all the devices at a facility.")
  fun listFacilityDevices(@PathVariable facilityId: FacilityId): ListDeviceConfigsResponse {
    val devices = deviceStore.fetchByFacilityId(facilityId)
    return ListDeviceConfigsResponse(
        devices.map { DeviceConfig(it, it.settings?.let { objectMapper.readValue(it.data()) }) })
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DeviceConfig(
    @Schema(description = "Unique identifier of this device.") val id: DeviceId,
    @Schema(description = "Identifier of facility where this device is located.")
    val facilityId: FacilityId,
    @Schema(
        description = "Name of this device. Should be unique within the facility.",
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
            "Protocol- and device-specific custom settings. This is an arbitrary JSON object; " +
                "the exact settings depend on the device type.")
    val settings: Map<String, Any?>?,
    @Schema(
        description = "How often the device manager should poll for status updates, in seconds.")
    val pollingInterval: Int?,
) {
  constructor(
      row: DevicesRow,
      settings: Map<String, Any?>?
  ) : this(
      id = row.id!!,
      facilityId = row.facilityId!!,
      name = row.name!!,
      type = row.deviceType!!,
      make = row.make!!,
      model = row.model!!,
      protocol = row.protocol,
      address = row.address,
      port = row.port,
      settings = settings,
      pollingInterval = row.pollingInterval,
  )
}

data class ListDeviceConfigsResponse(val devices: List<DeviceConfig>) : SuccessResponsePayload
