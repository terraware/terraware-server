package com.terraformation.seedbank.daily

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.AccessionFetcher
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.NotificationFetcher
import com.terraformation.seedbank.i18n.Messages
import com.terraformation.seedbank.services.atMostRecent
import com.terraformation.seedbank.services.perClassLogger
import java.time.Clock
import java.time.ZonedDateTime
import javax.annotation.ManagedBean
import org.springframework.context.event.EventListener

/** Generates summary notifications about accessions that are overdue for action. */
@ManagedBean
class StateSummaryNotificationTask(
    private val accessionFetcher: AccessionFetcher,
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val messages: Messages,
    private val notificationFetcher: NotificationFetcher
) {
  private val log = perClassLogger()

  @EventListener
  fun generateNotifications(
      @Suppress("UNUSED_PARAMETER") event: AccessionScheduledStateTask.FinishedEvent
  ): FinishedEvent {
    // We don't want to search an unbounded period of time, just cover any recent days when we
    // failed to generate notifications.
    val timeLimit = ZonedDateTime.now(clock).minusDays(30)

    val lastNotificationTime =
        notificationFetcher
            .getLastStateNotificationTime()
            ?.atZone(clock.zone)
            ?.coerceAtLeast(timeLimit)
            ?: timeLimit

    log.info("Generating state update notifications")

    pending(lastNotificationTime)
    processed(2, lastNotificationTime)
    processed(4, lastNotificationTime)
    dried(lastNotificationTime)

    return FinishedEvent()
  }

  private fun pending(lastNotificationTime: ZonedDateTime) {
    generateNotification(AccessionState.Pending, 1, lastNotificationTime) { count ->
      messages.longPendingNotification(count)
    }
  }

  private fun processed(weeks: Int, lastNotificationTime: ZonedDateTime) {
    // Accessions transition from Processing to Processed after 2 weeks have elapsed, but the
    // notification should be based on the time of the transition to Processing.
    generateNotification(AccessionState.Processed, weeks - 2, lastNotificationTime) { count ->
      messages.longProcessedNotification(count, weeks)
    }
  }

  private fun dried(lastNotificationTime: ZonedDateTime) {
    // Notification should go out the same day as the transition to Dried, so weeks is 0.
    generateNotification(AccessionState.Dried, 0, lastNotificationTime) { count ->
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
   * Thus in today's run, we want to notify about accessions that became overdue between January 8
   * and now.
   *
   * More precisely, because an accession becomes overdue when it reaches 1 week of pending status,
   * that means we want a count of accessions that went into Pending state on or before January 3 (1
   * week before now) and after January 1 (1 week before the last time we generated notifications).
   *
   * To implement "on or before January 3," we actually do "before January 4," that is, we turn the
   * equivalent of `x <= y` into `x < y+1`. This is because "on or before January 3" actually means
   * "at or before January 3 at 23:59:59.999999999999" and it's cleaner to compare with the start of
   * January 4 instead.
   *
   * The previous paragraph was a bit of a lie, though. Per the app's specifications, we don't
   * consider January 4 to start until 8AM. So the time calculations round all the time ranges down
   * to the previous 8AM, not to midnight. (You won't find 8AM here as a literal value; it is pulled
   * from [TerrawareServerConfig.dailyTasksStartTime].)
   */
  private fun generateNotification(
      state: AccessionState,
      weeks: Int,
      lastNotificationTime: ZonedDateTime,
      getMessage: (Int) -> String
  ) {
    val days = weeks * 7 - 1L
    val endOfAlreadyCoveredPeriod =
        lastNotificationTime.atMostRecent(config.dailyTasksStartTime).minusDays(days)
    val stateChangedBefore =
        ZonedDateTime.now(clock).atMostRecent(config.dailyTasksStartTime).minusDays(days)

    log.debug("Scanning for $state from $endOfAlreadyCoveredPeriod to $stateChangedBefore")

    val count =
        accessionFetcher.countInState(
            state, sinceAfter = endOfAlreadyCoveredPeriod, sinceBefore = stateChangedBefore)

    if (count > 0) {
      val message = getMessage(count)
      log.info("Generated notification: $message")
      notificationFetcher.insertStateNotification(state, message)
    } else {
      log.info("No notification needed for state $state at $weeks week(s)")
    }
  }

  /**
   * Published when the system has finished generating notifications with summaries of accession
   * states.
   */
  class FinishedEvent
}
