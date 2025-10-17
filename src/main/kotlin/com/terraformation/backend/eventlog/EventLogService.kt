package com.terraformation.backend.eventlog

import com.terraformation.backend.eventlog.db.EventLogStore
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.log.withMDC
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class EventLogService(
    private val eventLogStore: EventLogStore,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(event: PersistentEvent) {
    try {
      eventLogStore.insertEvent(event)
    } catch (e: Exception) {
      log.withMDC("event" to event.toString()) {
        log.error("Unable to store event of type ${event.javaClass.name}", e)
      }
    }
  }
}
