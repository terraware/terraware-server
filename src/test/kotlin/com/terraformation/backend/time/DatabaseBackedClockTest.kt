package com.terraformation.backend.time

import com.terraformation.backend.Application
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.tables.references.TEST_CLOCK
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.access.AccessDeniedException

internal class DatabaseBackedClockTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val config: TerrawareServerConfig = mockk()
  private val publisher = TestEventPublisher()
  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }

  /** Lazily-instantiated test subject; this will pick up per-test config values. */
  private val clock: DatabaseBackedClock by lazy { newDatabaseBackedClock() }

  private val applicationStartedEvent =
      ApplicationStartedEvent(
          SpringApplication(Application::class.java),
          emptyArray(),
          StaticApplicationContext(),
          Duration.ZERO,
      )

  @BeforeEach
  fun setup() {
    every { config.timeZone } returns ZoneOffset.UTC
    every { config.useTestClock } returns true
    every { user.canSetTestClock() } returns true
  }

  @Test
  fun `reads existing fake clock from database`() {
    dslContext
        .insertInto(TEST_CLOCK)
        .set(TEST_CLOCK.REAL_TIME, Instant.now())
        .set(TEST_CLOCK.FAKE_TIME, Instant.EPOCH)
        .execute()
    clock.initialize(applicationStartedEvent)
    assertSameInstant(Instant.EPOCH, clock.instant())
  }

  @Test
  fun `initializes database if no clock data`() {
    assertEquals(
        0,
        dslContext.selectFrom(TEST_CLOCK).fetch().size,
        "Clock table should be empty initially",
    )

    clock.initialize(applicationStartedEvent)
    val results = dslContext.selectFrom(TEST_CLOCK).fetch()
    assertEquals(1, results.size, "Number of rows in test clock table")
  }

  @Test
  fun `fake clock advances on its own`() {
    clock.initialize(applicationStartedEvent)

    val early = clock.instant()
    Thread.sleep(50)
    val later = clock.instant()

    assertNotEquals(early, later)
  }

  @Test
  fun `updates database when new time is set`() {
    dslContext
        .insertInto(TEST_CLOCK)
        .set(TEST_CLOCK.REAL_TIME, Instant.now())
        .set(TEST_CLOCK.FAKE_TIME, Instant.EPOCH)
        .execute()
    clock.initialize(applicationStartedEvent)

    val newFake = Instant.EPOCH.plus(1, ChronoUnit.DAYS)
    clock.setFakeTime(newFake)

    assertSameInstant(newFake, clock.instant(), "Time from existing instance")

    val newClock = newDatabaseBackedClock()
    newClock.initialize(applicationStartedEvent)
    assertSameInstant(newFake, newClock.instant(), "Time from fresh instance")
  }

  @Test
  fun `reset removes time offset`() {
    dslContext
        .insertInto(TEST_CLOCK)
        .set(TEST_CLOCK.REAL_TIME, Instant.now())
        .set(TEST_CLOCK.FAKE_TIME, Instant.EPOCH)
        .execute()
    clock.initialize(applicationStartedEvent)

    clock.reset()

    assertSameInstant(Instant.now(), clock.instant())
  }

  @Test
  fun `publishes event when clock is advanced`() {
    val expectedAdjustment = Duration.ofMinutes(1)

    clock.initialize(applicationStartedEvent)
    clock.advance(expectedAdjustment)

    publisher.assertEventPublished(ClockAdvancedEvent(expectedAdjustment))
  }

  @Test
  fun `publishes event when clock is reset`() {
    clock.initialize(applicationStartedEvent)
    clock.reset()

    publisher.assertEventPublished(ClockResetEvent())
  }

  @Test
  fun `does not allow clock to be advanced by a negative amount`() {
    clock.initialize(applicationStartedEvent)
    assertThrows<IllegalArgumentException> { clock.advance(Duration.ofSeconds(-1)) }
  }

  @Test
  fun `does not allow clock to be advanced to an earlier time`() {
    clock.initialize(applicationStartedEvent)
    assertThrows<IllegalArgumentException> {
      clock.setFakeTime(clock.instant() - Duration.ofSeconds(1))
    }
  }

  @Test
  fun `advance throws exception if no permission to set test clock`() {
    every { user.canSetTestClock() } returns false

    assertThrows<AccessDeniedException> { clock.advance(Duration.ofDays(5)) }
  }

  @Test
  fun `reset throws exception if no permission to set test clock`() {
    every { user.canSetTestClock() } returns false

    assertThrows<AccessDeniedException> { clock.reset() }
  }

  /** Tests for default pass-through behavior. */
  @Nested
  inner class TestClockNotEnabled {
    @BeforeEach
    fun useSystemClock() {
      every { config.useTestClock } returns false
      clock.initialize(applicationStartedEvent)
    }

    @Test
    fun `returns system time`() {
      assertSameInstant(Instant.now(), clock.instant())
    }

    @Test
    fun `throws exception when setting time`() {
      assertThrows<IllegalStateException> { clock.setFakeTime(Instant.now()) }
    }
  }

  /**
   * Asserts that two instants are within a maximum amount of time of one another. We can't use
   * strict equality because we're comparing clock calls and time will pass between two adjacent
   * reads of the clock. The threshold should be large enough to not cause tests to fail if they are
   * running on a busy CI server.
   */
  private fun assertSameInstant(
      expected: Instant,
      actual: Instant,
      message: String? = null,
      threshold: Duration = Duration.ofSeconds(10),
  ) {
    if (Duration.between(expected, actual) > threshold) {
      fail<Any> {
        listOfNotNull(
                message,
                "Difference between expected $expected and actual $actual is greater than " +
                    "maximum allowed $threshold",
            )
            .joinToString(": ")
      }
    }
  }

  private fun newDatabaseBackedClock(): DatabaseBackedClock {
    return DatabaseBackedClock(config, dslContext, publisher, systemUser, refreshInterval = null)
  }
}
