package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_NOTIFICATIONS
import com.terraformation.backend.tracking.event.ObservationNotScheduledNotificationEvent
import com.terraformation.backend.tracking.event.ObservationSchedulingNotificationEvent
import com.terraformation.backend.tracking.event.PlantingSeasonNotScheduledNotificationEvent
import com.terraformation.backend.tracking.event.PlantingSeasonNotScheduledSupportNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationReminderNotificationEvent
import org.jooq.Condition
import org.jooq.impl.DSL

interface NotificationCriteria {
  val notificationType: NotificationType
  val notificationNumber: Int

  /**
   * If true, [notificationNotCompletedCondition] requires that the planting site be owned by an
   * organization with the "Accelerator" internal tag.
   */
  val acceleratorOnly: Boolean
    get() = false

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
                .and(PLANTING_SITE_NOTIFICATIONS.NOTIFICATION_NUMBER.ge(notificationNumber))
        )

    val previousNotificationSent =
        if (requirePrevious && notificationNumber > 1) {
          DSL.exists(
              DSL.selectOne()
                  .from(PLANTING_SITE_NOTIFICATIONS)
                  .where(PLANTING_SITES.ID.eq(PLANTING_SITE_NOTIFICATIONS.PLANTING_SITE_ID))
                  .and(PLANTING_SITE_NOTIFICATIONS.NOTIFICATION_TYPE_ID.eq(notificationType))
                  .and(PLANTING_SITE_NOTIFICATIONS.NOTIFICATION_NUMBER.eq(notificationNumber - 1))
          )
        } else {
          null
        }

    val hasAcceleratorProject =
        if (acceleratorOnly) {
          DSL.exists(
              DSL.selectOne()
                  .from(PROJECTS)
                  .where(PROJECTS.ORGANIZATION_ID.eq(PLANTING_SITES.ORGANIZATION_ID))
                  .and(PROJECTS.PHASE_ID.isNotNull)
          )
        } else {
          null
        }

    return DSL.and(
        listOfNotNull(thisNotificationNotSent, previousNotificationSent, hasAcceleratorProject)
    )
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

  interface PlantingSeasonNotScheduledCriteria : NotificationCriteria {
    override val acceleratorOnly: Boolean
      get() = notificationType == NotificationType.PlantingSeasonNotScheduledSupport

    fun notificationEvent(plantingSiteId: PlantingSiteId) =
        if (notificationType == NotificationType.PlantingSeasonNotScheduledSupport) {
          PlantingSeasonNotScheduledSupportNotificationEvent(plantingSiteId, notificationNumber)
        } else {
          PlantingSeasonNotScheduledNotificationEvent(plantingSiteId, notificationNumber)
        }
  }

  data class FirstPlantingSeasonNotScheduled(
      override val notificationType: NotificationType,
      override val notificationNumber: Int,
      val weeksSinceCreation: Int,
  ) : PlantingSeasonNotScheduledCriteria {
    companion object {
      // These should be in descending time order to avoid sending multiple notifications.
      val notifications =
          listOf(
              FirstPlantingSeasonNotScheduled(
                  weeksSinceCreation = 14,
                  notificationType = NotificationType.PlantingSeasonNotScheduledSupport,
                  notificationNumber = 2,
              ),
              FirstPlantingSeasonNotScheduled(
                  weeksSinceCreation = 6,
                  notificationType = NotificationType.PlantingSeasonNotScheduledSupport,
                  notificationNumber = 1,
              ),
              FirstPlantingSeasonNotScheduled(
                  weeksSinceCreation = 4,
                  notificationType = NotificationType.SchedulePlantingSeason,
                  notificationNumber = 2,
              ),
              FirstPlantingSeasonNotScheduled(
                  weeksSinceCreation = 0,
                  notificationType = NotificationType.SchedulePlantingSeason,
                  notificationNumber = 1,
              ),
          )
    }
  }

  data class NextPlantingSeasonNotScheduled(
      override val notificationType: NotificationType,
      override val notificationNumber: Int,
      val weeksSinceLastSeason: Int,
  ) : PlantingSeasonNotScheduledCriteria {
    companion object {
      // These should be in descending time order to avoid sending multiple notifications.
      val notifications =
          listOf(
              NextPlantingSeasonNotScheduled(
                  weeksSinceLastSeason = 16,
                  notificationType = NotificationType.PlantingSeasonNotScheduledSupport,
                  notificationNumber = 2,
              ),
              NextPlantingSeasonNotScheduled(
                  weeksSinceLastSeason = 8,
                  notificationType = NotificationType.PlantingSeasonNotScheduledSupport,
                  notificationNumber = 1,
              ),
              NextPlantingSeasonNotScheduled(
                  weeksSinceLastSeason = 6,
                  notificationType = NotificationType.SchedulePlantingSeason,
                  notificationNumber = 2,
              ),
              NextPlantingSeasonNotScheduled(
                  weeksSinceLastSeason = 2,
                  notificationType = NotificationType.SchedulePlantingSeason,
                  notificationNumber = 1,
              ),
          )
    }
  }
}
