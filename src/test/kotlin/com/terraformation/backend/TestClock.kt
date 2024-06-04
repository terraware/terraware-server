package com.terraformation.backend

import jakarta.annotation.Priority
import jakarta.inject.Named
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Mutable clock for tests. Its time can be set on the fly; otherwise it acts as a fixed clock. */
@Named
@Priority(2)
class TestClock(
    var instant: Instant = Instant.EPOCH,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock(), BeanTestDouble {
  override fun instant(): Instant = instant

  override fun withZone(zone: ZoneId): Clock = TestClock(instant, zone)

  override fun getZone(): ZoneId = zone

  override fun resetState() {
    instant = Instant.EPOCH
  }
}
