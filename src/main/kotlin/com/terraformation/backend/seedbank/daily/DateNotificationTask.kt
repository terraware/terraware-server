package com.terraformation.backend.seedbank.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.daily.DailyTaskRunner
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.daily.NotificationJobFinishedEvent
import com.terraformation.backend.daily.NotificationJobStartedEvent
import com.terraformation.backend.daily.NotificationJobSucceededEvent
import com.terraformation.backend.daily.TimePeriodTask
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.event.AccessionDryingEndEvent
import java.time.Instant
import java.time.temporal.TemporalAccessor
import javax.inject.Named
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@Named
class DateNotificationTask(
    private val accessionStore: AccessionStore,
    private val dailyTaskRunner: DailyTaskRunner,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher
) : TimePeriodTask {
  private val log = perClassLogger()

  @EventListener
  fun generateNotifications(
      @Suppress("UNUSED_PARAMETER") event: DailyTaskTimeArrivedEvent
  ): NotificationJobFinishedEvent {
    dailyTaskRunner.runTask(this)
    return NotificationJobFinishedEvent()
  }

  override fun processPeriod(since: Instant, until: Instant) {
    log.info("Generating date update notifications for due dates since $since")
    eventPublisher.publishEvent(NotificationJobStartedEvent())

    dslContext.transaction { _ -> endDrying(since, until) }

    eventPublisher.publishEvent(NotificationJobSucceededEvent())
  }

  private fun endDrying(after: TemporalAccessor, until: TemporalAccessor) {
    accessionStore.fetchDryingEndDue(after, until).forEach { (number, id) ->
      eventPublisher.publishEvent(AccessionDryingEndEvent(number, id))
    }
  }
}
