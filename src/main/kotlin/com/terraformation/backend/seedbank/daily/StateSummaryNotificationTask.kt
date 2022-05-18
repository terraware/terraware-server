package com.terraformation.backend.seedbank.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.daily.DailyTaskRunner
import com.terraformation.backend.daily.TimePeriodTask
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.db.AccessionNotificationStore
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.event.AccessionsAwaitingProcessingEvent
import com.terraformation.backend.seedbank.event.AccessionsFinishedDryingEvent
import com.terraformation.backend.seedbank.event.AccessionsReadyForTestingEvent
import com.terraformation.backend.time.atMostRecent
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

/** Generates summary notifications about accessions that are overdue for action. */
@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@ManagedBean
class StateSummaryNotificationTask(
    private val accessionNotificationStore: AccessionNotificationStore,
    private val accessionStore: AccessionStore,
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val dailyTaskRunner: DailyTaskRunner,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val facilitiesDao: FacilitiesDao,
    private val messages: Messages,
) : TimePeriodTask {
  private val log = perClassLogger()

  @EventListener
  fun generateNotifications(
      @Suppress("UNUSED_PARAMETER") event: AccessionScheduledStateTask.FinishedEvent
  ): FinishedEvent {
    dailyTaskRunner.runTask(this)
    return FinishedEvent()
  }

  override fun processPeriod(since: Instant, until: Instant) {
    log.info("Generating state update notifications")

    val zonedSince = ZonedDateTime.ofInstant(since, clock.zone)

    dslContext.transaction { _ ->
      facilitiesDao.findAll().mapNotNull { it.id }.forEach { facilityId ->
        pending(facilityId, zonedSince)
        processed(facilityId, 2, zonedSince)
        processed(facilityId, 4, zonedSince)
        dried(facilityId, zonedSince)
      }
    }

    eventPublisher.publishEvent(PeriodProcessedEvent())
  }

  private fun pending(facilityId: FacilityId, lastNotificationTime: ZonedDateTime) {
    generateNotification(facilityId, AccessionState.Pending, 1, lastNotificationTime) { count ->
      messages.longPendingNotification(count)
    }
  }

  private fun processed(facilityId: FacilityId, weeks: Int, lastNotificationTime: ZonedDateTime) {
    // Accessions transition from Processing to Processed after 2 weeks have elapsed, but the
    // notification should be based on the time of the transition to Processing.
    generateNotification(facilityId, AccessionState.Processed, weeks - 2, lastNotificationTime) {
        count ->
      messages.longProcessedNotification(count, weeks)
    }
  }

  private fun dried(facilityId: FacilityId, lastNotificationTime: ZonedDateTime) {
    // Notification should go out the same day as the transition to Dried, so weeks is 0.
    generateNotification(facilityId, AccessionState.Dried, 0, lastNotificationTime) { count ->
      messages.driedNotification(count)
    }
  }

  /**
   * Generates notifications if there are accessions that have been in a particular state for a
   * particular number of weeks and that weren't covered by previously-generated notifications.
   *
   * The time calculations here are a bit complex, so an example, assuming we're generating the
   * notification about accessions being in the Pending state for over a week:
   *
   * It is currently January 10. We last generated notifications on January 8.
   *
   * Thus in today's run, we want to publish a notification if there are accessions that became
   * overdue between January 8 and now.
   *
   * More precisely, because an accession becomes overdue when it reaches 1 week of pending status,
   * that means we want to see if there are accessions that went into Pending state on or before
   * January 3 (1 week before now) and after January 1 (1 week before the last time we generated
   * notifications).
   *
   * To implement "on or before January 3," we actually do "before January 4," that is, we turn the
   * equivalent of `x <= y` into `x < y+1`. This is because "on or before January 3" actually means
   * "at or before January 3 at 23:59:59.999999999999" and it's cleaner to compare with the start of
   * January 4 instead.
   *
   * The previous paragraph was a bit of a lie, though. Per the app's specifications, we don't
   * consider January 4 to start until 8AM. So the time calculations round all the time ranges down
   * to the previous 8AM, not to midnight. (You won't find 8AM here as a literal value; it is pulled
   * from [TerrawareServerConfig.DailyTasksConfig.startTime].)
   */
  private fun generateNotification(
      facilityId: FacilityId,
      state: AccessionState,
      weeks: Int,
      lastNotificationTime: ZonedDateTime,
      getMessage: (Int) -> String
  ) {
    val days = weeks * 7 - 1L
    val endOfAlreadyCoveredPeriod =
        lastNotificationTime.atMostRecent(config.dailyTasks.startTime).minusDays(days)
    val stateChangedBefore =
        ZonedDateTime.now(clock).atMostRecent(config.dailyTasks.startTime).minusDays(days)

    log.debug(
        "Scanning for $state from $endOfAlreadyCoveredPeriod to $stateChangedBefore at " +
            "facility $facilityId")

    // We want to generate the notification if there are newly-overdue accessions, but the number
    // in the notification should be the total of all overdue accessions, including any that were
    // already covered by previous runs.
    val newCount =
        accessionStore.countInState(
            facilityId,
            state,
            sinceAfter = endOfAlreadyCoveredPeriod,
            sinceBefore = stateChangedBefore)
    if (newCount > 0) {
      val count = accessionStore.countInState(facilityId, state, sinceBefore = stateChangedBefore)

      val message = getMessage(count)
      log.info("Generated notification for facility $facilityId: $message")
      accessionNotificationStore.insertStateNotification(facilityId, state, message)
      when (state) {
        AccessionState.Pending ->
            eventPublisher.publishEvent(AccessionsAwaitingProcessingEvent(facilityId, count, state))
        AccessionState.Processed -> {
          if (weeks == 2) {
            eventPublisher.publishEvent(
                AccessionsReadyForTestingEvent(facilityId, count, weeks, state))
          }
        }
        AccessionState.Dried ->
            eventPublisher.publishEvent(AccessionsFinishedDryingEvent(facilityId, count, state))
        else -> log.warn("Unsupported state $state for notification events.")
      }
    }
  }

  /**
   * Published when the system has successfully finished generating notifications with summaries of
   * accession states for a period.
   */
  class PeriodProcessedEvent

  /**
   * Published when the system has finished generating notifications with summaries of accession
   * states regardless of error state.
   */
  class FinishedEvent
}
