package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ModuleEventStore
import com.terraformation.backend.accelerator.event.ModuleEventScheduledEvent
import com.terraformation.backend.accelerator.event.ModuleEventStartingEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.EventNotFoundException
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.InstantSource
import org.jobrunr.scheduling.JobScheduler
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener

@Named
class ModuleEventService(
    private val clock: InstantSource,
    private val eventPublisher: ApplicationEventPublisher,
    private val eventStore: ModuleEventStore,
    @Lazy private val scheduler: JobScheduler,
    private val systemUser: SystemUser,
) {
  companion object {
    private val log = perClassLogger()
  }

  /** Schedules an "event is starting" notification according to preset time. */
  @EventListener
  fun on(event: ModuleEventScheduledEvent) {
    systemUser.run {
      val moduleEvent =
          try {
            eventStore.fetchOneById(event.eventId)
          } catch (e: EventNotFoundException) {
            log.error("Module event ${event.eventId} not found.")
            return@run
          }

      val now = clock.instant()
      val startTime = moduleEvent.startTime
      val notifyTime = startTime.minus(MODULE_EVENT_NOTIFICATION_LEAD_TIME)
      val endTime = moduleEvent.endTime

      if (now.isBefore(notifyTime)) {
        scheduler.schedule<ModuleEventService>(notifyTime) {
          notifyStartingIfModuleEventUpToDate(event)
        }
        scheduler.schedule<ModuleEventService>(notifyTime) {
          updateEventStatusIfEventUpToDate(event, EventStatus.StartingSoon)
        }
      } else {
        log.warn(
            "Module event ${event.eventId} is starting before notification lead time. " +
                "No notifications sent to users."
        )
      }

      if (now.isBefore(startTime)) {
        scheduler.schedule<ModuleEventService>(startTime) {
          updateEventStatusIfEventUpToDate(event, EventStatus.InProgress)
        }
      } else {
        log.warn("Module event ${event.eventId} is already in progress. ")
      }

      if (now.isBefore(endTime)) {
        scheduler.schedule<ModuleEventService>(endTime) {
          updateEventStatusIfEventUpToDate(event, EventStatus.Ended)
        }
      } else {
        log.warn("Module event ${event.eventId} has already ended. ")
      }
    }
  }

  fun notifyStartingIfModuleEventUpToDate(event: ModuleEventScheduledEvent) {
    systemUser.run {
      val moduleEvent =
          try {
            eventStore.fetchOneById(event.eventId)
          } catch (e: EventNotFoundException) {
            log.info("Module event ${event.eventId} not found; it may have been deleted.")
            return@run
          }

      if (moduleEvent.revision == event.revision) {
        eventPublisher.publishEvent(ModuleEventStartingEvent(event.eventId))
      } else {
        log.info("Module event ${event.eventId} has been changed. Not notifying.")
      }
    }
  }

  fun updateEventStatusIfEventUpToDate(event: ModuleEventScheduledEvent, status: EventStatus) {
    systemUser.run {
      try {
        val moduleEvent = eventStore.fetchOneById(event.eventId)
        if (moduleEvent.revision == event.revision) {
          eventStore.updateEventStatus(event.eventId, status)
        } else {
          log.info("Module event ${event.eventId} has been changed. Not updating status.")
        }
      } catch (e: EventNotFoundException) {
        log.info("Module event ${event.eventId} not found; it may have been deleted.")
      } catch (e: Exception) {
        log.error("Update status for event ${event.eventId} failed.", e)
      }
    }
  }
}
