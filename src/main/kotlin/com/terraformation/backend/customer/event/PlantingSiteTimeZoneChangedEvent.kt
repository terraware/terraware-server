package com.terraformation.backend.customer.event

import com.terraformation.backend.tracking.model.PlantingSiteModel

/**
 * Published when a planting site's time zone has changed, either because it was individually
 * updated or because its organization's time zone was changed and the planting site doesn't have a
 * time zone of its own.
 */
data class PlantingSiteTimeZoneChangedEvent(val plantingSite: PlantingSiteModel)
