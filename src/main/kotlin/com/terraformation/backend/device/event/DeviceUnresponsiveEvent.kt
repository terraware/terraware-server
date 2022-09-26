package com.terraformation.backend.device.event

import com.terraformation.backend.db.default_schema.DeviceId
import java.time.Duration
import java.time.Instant

/** Published when a device manager reports that one of its devices isn't responding. */
data class DeviceUnresponsiveEvent(
    val deviceId: DeviceId,
    val lastRespondedTime: Instant?,
    val expectedInterval: Duration?,
)
