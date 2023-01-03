package com.terraformation.backend.customer.event

import com.terraformation.backend.customer.model.FacilityModel

/**
 * Published when a facility's time zone has changed, either because it was individually updated or
 * because its organization's time zone was changed and the facility doesn't have a time zone of its
 * own.
 */
data class FacilityTimeZoneChangedEvent(val facility: FacilityModel)
