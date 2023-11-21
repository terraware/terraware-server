package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId
import java.time.ZoneId

/** Published when an organization's time zone has been changed. */
data class OrganizationTimeZoneChangedEvent(
    val organizationId: OrganizationId,
    val oldTimeZone: ZoneId?,
    val newTimeZone: ZoneId?,
)
