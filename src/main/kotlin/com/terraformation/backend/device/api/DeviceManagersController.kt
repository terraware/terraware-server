package com.terraformation.backend.device.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.DeviceManagerAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.DeviceManagerId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.pojos.DeviceManagersRow
import com.terraformation.backend.device.DeviceManagerService
import com.terraformation.backend.device.db.DeviceManagerStore
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@DeviceManagerAppEndpoint
@RequestMapping("/api/v1/devices/managers")
@RestController
class DeviceManagersController(
    private val deviceManagerService: DeviceManagerService,
    private val deviceManagerStore: DeviceManagerStore,
) {
  @GetMapping
  fun getDeviceManagers(
      @RequestParam("sensorKitId") sensorKitId: String?,
      @RequestParam("facilityId") facilityId: FacilityId?,
  ): GetDeviceManagersResponsePayload {
    return when {
      sensorKitId != null && facilityId == null -> {
        val manager =
            deviceManagerStore.fetchOneBySensorKitId(sensorKitId)?.let { DeviceManagerPayload(it) }
        GetDeviceManagersResponsePayload(listOfNotNull(manager))
      }
      sensorKitId == null && facilityId != null -> {
        val manager =
            deviceManagerStore.fetchOneByFacilityId(facilityId)?.let { DeviceManagerPayload(it) }
        GetDeviceManagersResponsePayload(listOfNotNull(manager))
      }
      else -> throw IllegalArgumentException("Must specify sensorKitId or facility ID but not both")
    }
  }

  @GetMapping("/{deviceManagerId}")
  fun getDeviceManager(
      @PathVariable deviceManagerId: DeviceManagerId
  ): GetDeviceManagerResponsePayload {
    val manager = deviceManagerStore.fetchOneById(deviceManagerId)

    return GetDeviceManagerResponsePayload(DeviceManagerPayload(manager))
  }

  @PostMapping("/{deviceManagerId}/connect")
  fun connectDeviceManager(
      @PathVariable deviceManagerId: DeviceManagerId,
      @RequestBody payload: ConnectDeviceManagerRequestPayload,
  ): SimpleSuccessResponsePayload {
    deviceManagerService.connect(deviceManagerId, payload.facilityId)
    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DeviceManagerPayload(
    val id: DeviceManagerId,
    val sensorKitId: String,
    @Schema(description = "If true, this device manager is available to connect to a facility.")
    val available: Boolean,
    @Schema(
        description =
            "The facility this device manager is connected to, or null if it is not connected.")
    val facilityId: FacilityId?,
    @Schema(description = "If true, this device manager is currently online.")
    val isOnline: Boolean,
    @Schema(
        description =
            "When the device manager's isOnline value changed most recently. In other words, " +
                "if isOnline is true, the device manager has been online since this time; if " +
                "isOnline is false, the device manager has been offline since this time. This " +
                "may be null if the device manager has not come online for the first time yet.")
    val onlineChangedTime: Instant?,
    @Schema(
        description =
            "If an update is being downloaded or installed, its progress as a percentage. Not " +
                "present if no update is in progress.",
        minimum = "0",
        maximum = "100")
    val updateProgress: Int?,
) {
  constructor(
      row: DeviceManagersRow
  ) : this(
      available = row.facilityId == null,
      facilityId = row.facilityId,
      id = row.id!!,
      isOnline = row.isOnline!!,
      onlineChangedTime = row.lastConnectivityEvent,
      sensorKitId = row.sensorKitId!!,
      updateProgress = row.updateProgress,
  )
}

data class ConnectDeviceManagerRequestPayload(val facilityId: FacilityId)

data class GetDeviceManagerResponsePayload(val manager: DeviceManagerPayload) :
    SuccessResponsePayload

data class GetDeviceManagersResponsePayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "List of device managers that match the conditions in the request. Empty if " +
                        "there were no matches, e.g., the requested short code didn't exist."))
    val managers: List<DeviceManagerPayload>
) : SuccessResponsePayload
