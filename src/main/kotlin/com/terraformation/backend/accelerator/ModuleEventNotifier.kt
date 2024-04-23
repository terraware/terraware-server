package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ModuleEventStore
import com.terraformation.backend.accelerator.event.ModuleEventScheduledEvent
import com.terraformation.backend.accelerator.event.ModuleEventStartingEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.EventNotFoundException
import com.terraformation.backend.log.perClassLogger
import java.time.Duration
import java.time.InstantSource
import javax.inject.Named
import org.jobrunr.scheduling.JobScheduler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener

@Named
class ModuleEventNotifier(
    private val clock: InstantSource,
    private val eventPublisher: ApplicationEventPublisher,
    private val eventStore: ModuleEventStore,
    @Lazy private val scheduler: JobScheduler,
    private val systemUser: SystemUser,
) {
  companion object {
    private val log = perClassLogger()

    /** Notify upcoming events with the given lead time. */
    val notificationLeadTime: Duration = Duration.ofMinutes(15)
  }

  /** Schedules an "event is starting" notification according to preset time. */
  @EventListener
  fun on(event: ModuleEventScheduledEvent) {
    systemUser.run {
      val moduleEvent =
          try {
            eventStore.fetchEventById(event.eventId)
          } catch (e: EventNotFoundException) {
            log.error("Module event ${event.eventId} not found.")
            return@run
          }

      if (moduleEvent.startTime != null) {
        val notifyTime = moduleEvent.startTime.minus(notificationLeadTime)
        if (notifyTime.isAfter(clock.instant())) {
          scheduler.schedule<ModuleEventNotifier>(notifyTime) {
            notifyStartingIfModuleEventUpToDate(event)
          }
        } else {
          log.warn("Module event ${event.eventId} is starting past the notification lead time. ")
        }
      } else {
        log.error("Module event ${event.eventId} does not have a scheduled time.")
      }
    }
  }

  fun notifyStartingIfModuleEventUpToDate(event: ModuleEventScheduledEvent) {
    systemUser.run {
      val moduleEvent =
          try {
            eventStore.fetchEventById(event.eventId)
          } catch (e: EventNotFoundException) {
            log.error("Module event ${event.eventId} not found.")
            return@run
          }

      if (moduleEvent.revision == event.revision) {
        eventPublisher.publishEvent(ModuleEventStartingEvent(event.eventId))
      } else {
        log.info("Module event ${event.eventId} has been changed. Not notifying.")
      }
    }
  }
}
