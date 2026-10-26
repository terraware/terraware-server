package com.terraformation.backend.api

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer

/**
 * Custom deserializer for `Instant` fields that accepts either a full timestamp with time zone or a
 * local date-time string. In the latter case, the local time is interpreted as being in the UTC
 * time zone.
 *
 * This is a workaround for an issue where some mobile clients are not including time zones in some
 * of their timestamps.
 */
class UtcDefaultInstantDeserializer : ValueDeserializer<Instant?>() {
  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Instant? {
    val value = p.getString()
    if (value.isNullOrBlank()) {
      return null
    }

    return try {
      Instant.parse(value)
    } catch (e: DateTimeParseException) {
      // No zone/offset present, e.g. 2024-01-15T10:30:00 -> assume UTC
      LocalDateTime.parse(value).toInstant(ZoneOffset.UTC)
    }
  }
}
