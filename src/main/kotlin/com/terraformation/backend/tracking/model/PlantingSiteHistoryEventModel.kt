package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import java.time.Instant

enum class PlantingSiteHistoryEventType {
  Created,
  Edited,
}

/** A change to a planting site's map, derived from a row in the site's history. */
data class PlantingSiteHistoryEventModel(
    val createdBy: UserId,
    val createdByName: String?,
    val createdTime: Instant,
    val historyId: PlantingSiteHistoryId,
    val type: PlantingSiteHistoryEventType,
)
