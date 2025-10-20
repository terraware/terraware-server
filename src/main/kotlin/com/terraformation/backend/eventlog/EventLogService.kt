package com.terraformation.backend.eventlog

import com.terraformation.backend.eventlog.db.EventLogStore
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener

@Named
class EventLogService(
    private val eventLogStore: EventLogStore,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(event: PersistentEvent) {
    val message = event.toMessage() ?: "Published ${event.javaClass.simpleName}"
    val eventLogger = LoggerFactory.getLogger(event.javaClass)
    eventLogger.atInfo().addKeyValue("event", event).log(message)

    try {
      eventLogStore.insertEvent(event)
    } catch (e: Exception) {
      log.error("Unable to store event of type ${event.javaClass.name}", e)
    }
  }
}
