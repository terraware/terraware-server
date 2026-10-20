package com.terraformation.backend

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

/**
 * Detects test runs that have stopped making progress. If no test starts or finishes for
 * [hangTimeout], writes a thread dump and Docker status report using [TestDiagnostics].
 *
 * On CI, the test JVM is then terminated so the build fails rather than waiting for the job to time
 * out. Locally, the run is left alone so it can be inspected with a debugger, and another report is
 * written each time the timeout elapses again.
 *
 * The timeout can be changed with the `terraware.test.hangTimeout` system property, which takes an
 * ISO-8601 duration such as `PT5M`.
 */
class TestHangWatchdog : TestExecutionListener {
  private val hangTimeout: Duration =
      System.getProperty("terraware.test.hangTimeout")?.let { Duration.parse(it) }
          ?: Duration.ofMinutes(10)
  private val exitOnHang = System.getenv("CI") != null
  private val lastActivity = AtomicReference(Instant.now())

  @Volatile private var running = false

  override fun testPlanExecutionStarted(testPlan: TestPlan) {
    lastActivity.set(Instant.now())
    running = true

    thread(isDaemon = true, name = "test-hang-watchdog") {
      while (running) {
        Thread.sleep(Duration.ofSeconds(10))

        val idleTime = Duration.between(lastActivity.get(), Instant.now())
        if (running && idleTime >= hangTimeout) {
          reportHang(idleTime)
          lastActivity.set(Instant.now())
        }
      }
    }
  }

  override fun testPlanExecutionFinished(testPlan: TestPlan) {
    running = false
  }

  override fun executionStarted(testIdentifier: TestIdentifier) {
    lastActivity.set(Instant.now())
  }

  override fun executionFinished(
      testIdentifier: TestIdentifier,
      testExecutionResult: TestExecutionResult,
  ) {
    lastActivity.set(Instant.now())
  }

  override fun executionSkipped(testIdentifier: TestIdentifier, reason: String?) {
    lastActivity.set(Instant.now())
  }

  private fun reportHang(idleTime: Duration) {
    val path =
        TestDiagnostics.writeReport(
            "test-hang",
            "No tests have started or finished in $idleTime\n\n" +
                TestDiagnostics.dockerDiagnostics() +
                "\n" +
                TestDiagnostics.threadDump(),
        )

    if (exitOnHang) {
      System.err.println("Test run appears to be stuck; exiting. Diagnostics are in $path")
      System.err.flush()
      Runtime.getRuntime().halt(1)
    }
  }
}
