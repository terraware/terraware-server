package com.terraformation.backend.device.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.ArbitraryJsonObject
import com.terraformation.backend.api.DeviceManagerAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import com.terraformation.backend.device.DeviceService
import com.terraformation.backend.device.db.DeviceStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Duration
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@DeviceManagerAppEndpoint
@RestController
class DevicesController(
    private val deviceService: DeviceService,
    private val deviceStore: DeviceStore,
) {
  @ApiResponse(responseCode = "200", description = "Successfully listed the facility's devices.")
  @ApiResponse404(
      description = "The facility does not exist or is not accessible by the current user."
  )
  @GetMapping("/api/v1/facility/{facilityId}/devices", "/api/v1/facilities/{facilityId}/devices")
  @Operation(summary = "Lists the configurations of all the devices at a facility.")
  fun listFacilityDevices(@PathVariable facilityId: FacilityId): ListDeviceConfigsResponse {
    val devices = deviceStore.fetchByFacilityId(facilityId)
    return ListDeviceConfigsResponse(devices.map { DeviceConfig(it) })
  }

  @ApiResponseSimpleSuccess
  @Operation(summary = "Registers a new device a facility's device manager.")
  @PostMapping("/api/v1/devices")
  fun createDevice(@RequestBody payload: CreateDeviceRequestPayload): CreateDeviceResponsePayload {
    val devicesRow = payload.toRow()
    val deviceId = deviceService.create(devicesRow)
    return CreateDeviceResponsePayload(deviceId)
  }

  @ApiResponse(responseCode = "200", description = "Device configuration retrieved.")
  @ApiResponse404
  @GetMapping("/api/v1/devices/{id}")
  @Operation(summary = "Gets the configuration of a single device.")
  fun getDevice(@PathVariable("id") deviceId: DeviceId): GetDeviceResponsePayload {
    val devicesRow = deviceStore.fetchOneById(deviceId)
    return GetDeviceResponsePayload(DeviceConfig(devicesRow))
  }

  @ApiResponse(responseCode = "200", description = "Device configuration updated.")
  @ApiResponse404
  @Operation(summary = "Updates the configuration of an existing device.")
  @PutMapping("/api/v1/devices/{id}")
  fun updateDevice(
      @PathVariable("id") deviceId: DeviceId,
      @RequestBody payload: UpdateDeviceRequestPayload,
  ): SimpleSuccessResponsePayload {
    val devicesRow = payload.toRow(deviceId)
    deviceService.update(devicesRow)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @Operation(
      summary = "Marks a device as unresponsive.",
      description = "Notifies the appropriate users so they can troubleshoot the problem.",
  )
  @PostMapping("/api/v1/devices/{id}/unresponsive")
  fun deviceUnresponsive(
      @PathVariable("id") deviceId: DeviceId,
      @RequestBody payload: DeviceUnresponsiveRequestPayload,
  ): SimpleSuccessResponsePayload {
    deviceService.markUnresponsive(
        deviceId,
        payload.lastRespondedTime,
        payload.expectedIntervalSecs?.let { Duration.ofSeconds(it.toLong()) },
    )
    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DeviceConfig(
    @Schema(
        description = "Unique identifier of this device.",
    )
    val id: DeviceId,
    @Schema(description = "Identifier of facility where this device is located.")
    val facilityId: FacilityId,
    @Schema(
        description = "Name of this device.",
        example = "BMU-1",
    )
    val name: String,
    @Schema(
        description =
            "High-level type of the device. Device manager may use this in conjunction " +
                "with the make and model to determine which metrics to report.",
        example = "inverter",
    )
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
        example = "192.168.1.100",
    )
    val address: String?,
    @Schema(description = "Port number if relevant for the protocol.", example = "50000")
    val port: Int?,
    @Schema(
        description =
            "Protocol- and device-specific custom settings. This is an arbitrary JSON object; " +
                "the exact settings depend on the device type."
    )
    val settings: ArbitraryJsonObject?,
    @Schema(
        description = "Level of diagnostic information to log.",
    )
    val verbosity: Int?,
    @Schema(description = "ID of parent device such as a hub or gateway, if any.")
    val parentId: DeviceId?,
) {
  constructor(
      row: DevicesRow
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
      settings = row.settings,
      verbosity = row.verbosity,
      parentId = row.parentId,
  )
}

data class CreateDeviceRequestPayload(
    @Schema(description = "Identifier of facility where this device is located.")
    val facilityId: FacilityId,
    @Schema(
        description = "Name of this device.",
        example = "BMU-1",
    )
    val name: String,
    @Schema(
        description =
            "High-level type of the device. Device manager may use this in conjunction " +
                "with the make and model to determine which metrics to report.",
        example = "inverter",
    )
    val type: String,
    @Schema(description = "Name of device manufacturer.", example = "InHand Networks")
    val make: String,
    @Schema(description = "Model number or model name of the device.", example = "IR915L")
    val model: String,
    @Schema(description = "Device manager protocol name.", example = "modbus")
    val protocol: String? = null,
    @Schema(
        description =
            "Protocol-specific address of device, e.g., an IP address or a Bluetooth device ID.",
        example = "192.168.1.100",
    )
    val address: String? = null,
    @Schema(description = "Port number if relevant for the protocol.", example = "50000")
    val port: Int? = null,
    @Schema(
        description =
            "Protocol- and device-specific custom settings. This is an arbitrary JSON object; " +
                "the exact settings depend on the device type."
    )
    val settings: ArbitraryJsonObject? = null,
    @Schema(
        description = "Level of diagnostic information to log.",
    )
    val verbosity: Int?,
    @Schema(
        description =
            "ID of parent device such as a hub or gateway, if any. The parent device must exist."
    )
    val parentId: DeviceId? = null,
) {
  fun toRow(): DevicesRow {
    return DevicesRow(
        id = null,
        facilityId = facilityId,
        name = name,
        deviceType = type,
        make = make,
        model = model,
        protocol = protocol,
        address = address,
        port = port,
        verbosity = verbosity,
        enabled = true,
        settings = settings,
        parentId = parentId,
    )
  }
}

data class UpdateDeviceRequestPayload(
    @Schema(
        description = "Name of this device.",
        example = "BMU-1",
    )
    val name: String,
    @Schema(
        description =
            "High-level type of the device. Device manager may use this in conjunction " +
                "with the make and model to determine which metrics to report.",
        example = "inverter",
    )
    val type: String,
    @Schema(description = "Name of device manufacturer.", example = "InHand Networks")
    val make: String,
    @Schema(description = "Model number or model name of the device.", example = "IR915L")
    val model: String,
    @Schema(description = "Device manager protocol name.", example = "modbus")
    val protocol: String? = null,
    @Schema(
        description =
            "Protocol-specific address of device, e.g., an IP address or a Bluetooth device ID.",
        example = "192.168.1.100",
    )
    val address: String? = null,
    @Schema(description = "Port number if relevant for the protocol.", example = "50000")
    val port: Int? = null,
    @Schema(
        description =
            "Protocol- and device-specific custom settings. This is an arbitrary JSON object; " +
                "the exact settings depend on the device type."
    )
    val settings: ArbitraryJsonObject? = null,
    @Schema(
        description = "Level of diagnostic information to log.",
    )
    val verbosity: Int?,
    @Schema(
        description =
            "ID of parent device such as a hub or gateway, if any. The parent device must exist."
    )
    val parentId: DeviceId? = null,
) {
  fun toRow(deviceId: DeviceId): DevicesRow {
    return DevicesRow(
        id = deviceId,
        facilityId = null,
        name = name,
        deviceType = type,
        make = make,
        model = model,
        protocol = protocol,
        address = address,
        port = port,
        verbosity = verbosity,
        enabled = true,
        settings = settings,
        parentId = parentId,
    )
  }
}

data class DeviceUnresponsiveRequestPayload(
    @Schema(
        description =
            "When the device most recently responded. Null or absent if the device has never " +
                "responded."
    )
    val lastRespondedTime: Instant?,
    @Schema(
        description =
            "The expected amount of time between updates from the device. Null or absent if " +
                "there is no fixed update interval."
    )
    val expectedIntervalSecs: Int?,
)

data class GetDeviceResponsePayload(val device: DeviceConfig) : SuccessResponsePayload

data class ListDeviceConfigsResponse(val devices: List<DeviceConfig>) : SuccessResponsePayload

data class CreateDeviceResponsePayload(val id: DeviceId) : SuccessResponsePayload
