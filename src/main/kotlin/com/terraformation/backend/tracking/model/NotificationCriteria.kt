package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.records.PlantingSitesRecord
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.tracking.event.ObservationSchedulingNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationReminderNotificationEvent
import java.time.Instant
import org.jooq.Condition
import org.jooq.TableField
import org.jooq.impl.DSL

class NotificationCriteria {

  sealed interface ObservationScheduling {
    val completedTimeElapsedWeeks: Long
    val firstPlantingElapsedWeeks: Long
    val notificationNotCompletedCondition: Condition
    val notificationCompletedField: TableField<PlantingSitesRecord, Instant?>
    fun notificationEvent(plantingSiteId: PlantingSiteId): ObservationSchedulingNotificationEvent
  }

  object ScheduleObservations : ObservationScheduling {
    override val completedTimeElapsedWeeks: Long = 2
    override val firstPlantingElapsedWeeks: Long = 0
    override val notificationNotCompletedCondition: Condition =
        DSL.condition(PLANTING_SITES.SCHEDULE_OBSERVATION_NOTIFICATION_SENT_TIME.isNull)
    override val notificationCompletedField: TableField<PlantingSitesRecord, Instant?> =
        PLANTING_SITES.SCHEDULE_OBSERVATION_NOTIFICATION_SENT_TIME

    override fun notificationEvent(plantingSiteId: PlantingSiteId) =
        ScheduleObservationNotificationEvent(plantingSiteId)
  }

  object RemindSchedulingObservations : ObservationScheduling {
    override val completedTimeElapsedWeeks: Long = 6
    override val firstPlantingElapsedWeeks: Long = 4
    override val notificationNotCompletedCondition: Condition =
        DSL.condition(PLANTING_SITES.SCHEDULE_OBSERVATION_NOTIFICATION_SENT_TIME.isNotNull)
            .and(PLANTING_SITES.SCHEDULE_OBSERVATION_REMINDER_NOTIFICATION_SENT_TIME.isNull)
    override val notificationCompletedField: TableField<PlantingSitesRecord, Instant?> =
        PLANTING_SITES.SCHEDULE_OBSERVATION_REMINDER_NOTIFICATION_SENT_TIME

    override fun notificationEvent(plantingSiteId: PlantingSiteId) =
        ScheduleObservationReminderNotificationEvent(plantingSiteId)
  }
}
