package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.eventlog.PersistentEvent

/**
 * Common supertype for events that are scoped to a planting season. This lets the notification
 * machinery fetch and group the different kinds of planting season events together by
 * [plantingSeasonId].
 */
interface PlantingSeasonRelatedPersistentEvent : PersistentEvent {
  val organizationId: OrganizationId
  val plantingSeasonId: PlantingSeasonId
  val plantingSiteId: PlantingSiteId
}
