package com.terraformation.backend.device.balena

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import java.time.Instant

/** Information about a device from the Balena API. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class BalenaDevice(
    val deviceName: String,
    val id: BalenaDeviceId,
    val isActive: Boolean,
    val isOnline: Boolean,
    val lastConnectivityEvent: Instant?,
    val modifiedAt: Instant,
    val overallProgress: Int?,
    val provisioningState: String?,
    val status: String?,
    val uuid: String,
) {
  companion object {
    /**
     * Names of the fields to request from the Balena API, corresponding to the properties of the
     * class. We need to pass a field list explicitly because Balena doesn't include some fields in
     * its default responses.
     */
    val selectFields =
        listOf(
            "device_name",
            "id",
            "is_active",
            "is_online",
            "last_connectivity_event",
            "modified_at",
            "overall_progress",
            "provisioning_state",
            "status",
            "uuid",
        )
  }
}
