package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.event.ModuleEventScheduledEvent
import com.terraformation.backend.accelerator.event.ModuleEventStartingEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.log.perClassLogger
import java.time.Duration
import java.time.InstantSource
import javax.inject.Named
import org.jobrunr.scheduling.JobScheduler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener

@Named
class EventNotifier(
    private val clock: InstantSource,
    private val eventPublisher: ApplicationEventPublisher,
    @Lazy private val scheduler: JobScheduler,
    private val moduleStore: ModuleStore,
    private val systemUser: SystemUser,
) {
  companion object {
    /** Notify upcoming events with the given lead time. */
    private val notificationLeadTime = Duration.ofMinutes(15)
    private val log = perClassLogger()
  }

  /** Schedules an "event is starting" notification according to preset time. */
  @EventListener
  fun on(event: ModuleEventScheduledEvent) {
    val moduleEvent = moduleStore.fetchEventById(event.eventId)
    if (moduleEvent?.startTime != null) {
      val notifyTime = moduleEvent.startTime.minus(notificationLeadTime)
      if (notifyTime.isAfter(clock.instant())) {
        scheduler.schedule<EventNotifier>(notifyTime) {
          systemUser.run {
            eventPublisher.publishEvent(
                ModuleEventStartingEvent(
                    event.eventId, notificationLeadTime, moduleEvent.startTime))
          }
        }
      } else {
        log.error("Module event ${event.eventId} is starting past the notification lead time.")
      }
    } else {
      // This can happen if an event is deleted, or a previously scheduled time is removed.
      log.error("Module event ${event.eventId} not found or not scheduled.")
    }
  }
}
