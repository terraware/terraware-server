package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_NOTIFICATIONS
import com.terraformation.backend.tracking.event.ObservationNotScheduledNotificationEvent
import com.terraformation.backend.tracking.event.ObservationSchedulingNotificationEvent
import com.terraformation.backend.tracking.event.PlantingSeasonNotScheduledNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationReminderNotificationEvent
import org.jooq.Condition
import org.jooq.impl.DSL

interface NotificationCriteria {
  val notificationType: NotificationType
  val notificationNumber: Int

  /**
   * Returns a query condition that evaluates to true if this notification hasn't been marked as
   * completed yet.
   *
   * @param requirePrevious If true, also require the notification with the previous number to be
   *   marked as completed if [notificationNumber] is greater than 1.
   */
  fun notificationNotCompletedCondition(requirePrevious: Boolean = false): Condition {
    val thisNotificationNotSent =
        DSL.notExists(
            DSL.selectOne()
                .from(PLANTING_SITE_NOTIFICATIONS)
                .where(PLANTING_SITES.ID.eq(PLANTING_SITE_NOTIFICATIONS.PLANTING_SITE_ID))
                .and(PLANTING_SITE_NOTIFICATIONS.NOTIFICATION_TYPE_ID.eq(notificationType))
                .and(PLANTING_SITE_NOTIFICATIONS.NOTIFICATION_NUMBER.ge(notificationNumber)))

    return if (requirePrevious && notificationNumber > 1) {
      val previousNotificationSent =
          DSL.exists(
              DSL.selectOne()
                  .from(PLANTING_SITE_NOTIFICATIONS)
                  .where(PLANTING_SITES.ID.eq(PLANTING_SITE_NOTIFICATIONS.PLANTING_SITE_ID))
                  .and(PLANTING_SITE_NOTIFICATIONS.NOTIFICATION_TYPE_ID.eq(notificationType))
                  .and(PLANTING_SITE_NOTIFICATIONS.NOTIFICATION_NUMBER.eq(notificationNumber - 1)))

      DSL.and(previousNotificationSent, thisNotificationNotSent)
    } else {
      thisNotificationNotSent
    }
  }

  sealed interface ObservationScheduling : NotificationCriteria {
    val completedTimeElapsedWeeks: Long
    val firstPlantingElapsedWeeks: Long
    fun notificationEvent(plantingSiteId: PlantingSiteId): ObservationSchedulingNotificationEvent
  }

  data object ScheduleObservations : ObservationScheduling {
    override val completedTimeElapsedWeeks: Long = 2
    override val firstPlantingElapsedWeeks: Long = 0
    override val notificationType: NotificationType = NotificationType.ScheduleObservation
    override val notificationNumber: Int = 1

    override fun notificationEvent(plantingSiteId: PlantingSiteId) =
        ScheduleObservationNotificationEvent(plantingSiteId)
  }

  data object RemindSchedulingObservations : ObservationScheduling {
    override val completedTimeElapsedWeeks: Long = 6
    override val firstPlantingElapsedWeeks: Long = 4
    override val notificationType: NotificationType = NotificationType.ScheduleObservation
    override val notificationNumber: Int = 2

    override fun notificationEvent(plantingSiteId: PlantingSiteId) =
        ScheduleObservationReminderNotificationEvent(plantingSiteId)
  }

  data object ObservationNotScheduledFirstNotification : ObservationScheduling {
    override val completedTimeElapsedWeeks: Long = 8
    override val firstPlantingElapsedWeeks: Long = 6
    override val notificationType: NotificationType =
        NotificationType.ObservationNotScheduledSupport
    override val notificationNumber: Int = 1

    override fun notificationEvent(plantingSiteId: PlantingSiteId) =
        ObservationNotScheduledNotificationEvent(plantingSiteId)
  }

  data object ObservationNotScheduledSecondNotification : ObservationScheduling {
    override val completedTimeElapsedWeeks: Long = 16
    override val firstPlantingElapsedWeeks: Long = 14
    override val notificationType: NotificationType =
        NotificationType.ObservationNotScheduledSupport
    override val notificationNumber: Int = 2

    override fun notificationEvent(plantingSiteId: PlantingSiteId) =
        ObservationNotScheduledNotificationEvent(plantingSiteId)
  }

  data class FirstPlantingSeasonNotScheduled(
      override val notificationNumber: Int,
      val weeksSinceCreation: Int
  ) : NotificationCriteria {
    override val notificationType
      get() = NotificationType.SchedulePlantingSeason

    fun notificationEvent(plantingSiteId: PlantingSiteId) =
        PlantingSeasonNotScheduledNotificationEvent(plantingSiteId, notificationNumber)

    companion object {
      // These should be in descending notification number order to avoid sending multiple
      // notifications.
      val notifications =
          listOf(
              FirstPlantingSeasonNotScheduled(notificationNumber = 2, weeksSinceCreation = 4),
              FirstPlantingSeasonNotScheduled(notificationNumber = 1, weeksSinceCreation = 0),
          )
    }
  }

  data class NextPlantingSeasonNotScheduled(
      override val notificationNumber: Int,
      val weeksSinceLastSeason: Int
  ) : NotificationCriteria {
    override val notificationType: NotificationType
      get() = NotificationType.SchedulePlantingSeason

    fun notificationEvent(plantingSiteId: PlantingSiteId) =
        PlantingSeasonNotScheduledNotificationEvent(plantingSiteId, notificationNumber)

    companion object {
      // These should be in descending notification number order to avoid sending multiple
      // notifications.
      val notifications =
          listOf(
              NextPlantingSeasonNotScheduled(notificationNumber = 2, weeksSinceLastSeason = 6),
              NextPlantingSeasonNotScheduled(notificationNumber = 1, weeksSinceLastSeason = 2),
          )
    }
  }
}
