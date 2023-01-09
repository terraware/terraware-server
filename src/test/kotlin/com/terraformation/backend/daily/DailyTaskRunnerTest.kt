package com.terraformation.backend.daily

import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.time.ClockResetEvent
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class DailyTaskRunnerTest : DatabaseTest() {
  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private val publisher = TestEventPublisher()

  private lateinit var dailyTaskRunner: DailyTaskRunner
  private lateinit var systemUser: SystemUser

  private lateinit var task: TimePeriodTask

  private val now = Instant.parse("2021-01-01T00:00:00Z")
  private val maximumPeriod = Duration.ofDays(7)
  private val startTimeForFirstRun = now - maximumPeriod
  private val timeoutPeriod = Duration.ofHours(4)

  @BeforeEach
  fun setUp() {
    clock.instant = now

    task = makeMockTask()

    systemUser = SystemUser(usersDao)
    dailyTaskRunner = DailyTaskRunner(clock, config, dslContext, publisher, systemUser)
  }

  /**
   * Returns a mock task that can be passed to the daily task runner then verified.
   *
   * The typical pattern in these tests is
   * 1. Customize behavior of the mock, e.g., by making its `processPeriod` method perform an action
   *    or do an assertion.
   * 2. Call `dailyTaskRunner.runTask(task)`.
   * 3. Assert that `processPeriod` was actually called if it should have been, or that it wasn't
   *    called if it shouldn't have been.
   */
  private fun makeMockTask(name: String? = "task"): TimePeriodTask {
    val task: TimePeriodTask = mockk(name = name)

    every { task.maximumPeriod } returns maximumPeriod
    justRun { task.processPeriod(any(), any()) }
    every { task.startTimeForFirstRun(any()) } returns startTimeForFirstRun
    every { task.timeoutPeriod } returns timeoutPeriod

    return task
  }

  @Test
  fun `runs task that hasn't been run before with correct initial time period`() {
    val sinceSlot: CapturingSlot<Instant> = slot()
    val untilSlot: CapturingSlot<Instant> = slot()
    justRun { task.processPeriod(capture(sinceSlot), capture(untilSlot)) }

    dailyTaskRunner.runTask(task)

    verify { task.processPeriod(any(), any()) }
    assertEquals(startTimeForFirstRun, sinceSlot.captured, "Since")
    assertEquals(startTimeForFirstRun + maximumPeriod, untilSlot.captured, "Until")
  }

  @Test
  fun `skips task that is already in progress`() {
    every { task.processPeriod(any(), any()) } answers
        {
          // The task should already be marked as in progress, so this shouldn't try running it
          // again.
          dailyTaskRunner.runTask(task)
        }

    dailyTaskRunner.runTask(task)

    verify(exactly = 1) { task.processPeriod(any(), any()) }
  }

  @Test
  fun `runs task that has been in progress too long`() {
    every { task.processPeriod(any(), any()) } answers
        {
          clock.instant = now + timeoutPeriod + Duration.ofSeconds(1)
          justRun { task.processPeriod(any(), any()) }
          dailyTaskRunner.runTask(task)
        }

    dailyTaskRunner.runTask(task)

    verify(exactly = 2) { task.processPeriod(any(), any()) }
  }

  @Test
  fun `different tasks can run concurrently`() {
    val otherTask = makeMockTask("other")

    every { task.processPeriod(any(), any()) } answers
        {
          dailyTaskRunner.runTask(otherTask, "other")
        }

    dailyTaskRunner.runTask(task)

    verify(exactly = 1) { task.processPeriod(any(), any()) }
    verify(exactly = 1) { otherTask.processPeriod(any(), any()) }
  }

  @Test
  fun `runs tasks as system user`() {
    every { task.processPeriod(any(), any()) } answers
        {
          assertEquals(systemUser.userId, currentUser().userId)
        }

    dailyTaskRunner.runTask(task)

    verify(exactly = 1) { task.processPeriod(any(), any()) }
  }

  @Test
  fun `resetting clock causes next task run to scan for work based on updated time`() {
    justRun { task.processPeriod(any(), any()) }

    dailyTaskRunner.runTask(task)

    val pastTime = now - Duration.ofDays(5)
    clock.instant = pastTime

    dailyTaskRunner.handle(ClockResetEvent())

    dailyTaskRunner.runTask(task)

    verify(exactly = 1) { task.processPeriod(any(), pastTime) }
  }
}
