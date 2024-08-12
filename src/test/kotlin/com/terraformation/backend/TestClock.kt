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
    private val initialInstant: Instant = Instant.EPOCH,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock(), BeanTestDouble {
  /**
   * The clock's current value is tracked per thread since two tests running in parallel could share
   * the same Spring-managed instance of this class, and they shouldn't interact with each other.
   */
  private val threadInstant = ThreadLocal.withInitial { initialInstant }

  var instant: Instant
    get() = threadInstant.get()
    set(value) {
      threadInstant.set(value)
    }

  override fun instant(): Instant = instant

  override fun withZone(zone: ZoneId): Clock = TestClock(instant, zone)

  override fun getZone(): ZoneId = zone

  override fun resetState() {
    threadInstant.remove()
  }
}
