package com.terraformation.backend.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionsTest {
  @Test
  fun `toInstant converts date to correct instant`() {
    val date = LocalDate.of(2023, 1, 1)
    val time = LocalTime.of(2, 3, 4, 0)
    val timeZone = ZoneId.of("America/Los_Angeles")

    assertEquals(Instant.ofEpochSecond(1672567384), date.toInstant(timeZone, time))
  }
}
