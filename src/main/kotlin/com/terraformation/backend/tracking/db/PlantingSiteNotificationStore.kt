package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_NOTIFICATIONS
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext

@Named
class PlantingSiteNotificationStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun markNotificationComplete(
      plantingSiteId: PlantingSiteId,
      notificationType: NotificationType,
      notificationNumber: Int,
  ) {
    requirePermissions {
      readPlantingSite(plantingSiteId)
      manageNotifications()
    }

    with(PLANTING_SITE_NOTIFICATIONS) {
      dslContext
          .insertInto(PLANTING_SITE_NOTIFICATIONS)
          .set(PLANTING_SITE_ID, plantingSiteId)
          .set(NOTIFICATION_TYPE_ID, notificationType)
          .set(NOTIFICATION_NUMBER, notificationNumber)
          .set(SENT_TIME, clock.instant())
          .execute()
    }
  }
}
