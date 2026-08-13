package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CreateAccessionRequestPayloadV2Test {
  private val objectMapper = jacksonObjectMapper()

  @Test
  fun `accepts collected time with time zone`() {
    val payload =
        objectMapper.readValue<CreateAccessionRequestPayloadV2>(
            """{ "collectedTime": "2026-08-13T11:08:00-08:00", "facilityId": 1 }"""
        )

    assertEquals(
        ZonedDateTime.of(2026, 8, 13, 11, 8, 0, 0, ZoneOffset.ofHours(-8)).toInstant(),
        payload.collectedTime,
    )
  }

  @Test
  fun `treats collected time without time zone as UTC`() {
    val payload =
        objectMapper.readValue<CreateAccessionRequestPayloadV2>(
            """{ "collectedTime": "2026-08-13T11:08:00", "facilityId": 1 }"""
        )

    assertEquals(
        ZonedDateTime.of(2026, 8, 13, 11, 8, 0, 0, ZoneOffset.UTC).toInstant(),
        payload.collectedTime,
    )
  }
}
