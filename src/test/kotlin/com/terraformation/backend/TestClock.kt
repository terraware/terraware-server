package com.terraformation.backend

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Mutable clock for tests. Its time can be set on the fly; otherwise it acts as a fixed clock. */
class TestClock(
    var instant: Instant = Instant.EPOCH,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
  override fun instant(): Instant = instant

  override fun withZone(zone: ZoneId): Clock = TestClock(instant, zone)

  override fun getZone(): ZoneId = zone
}
