package com.terraformation.backend

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Collects diagnostic information when the test suite appears to be stuck. Reports are written to
 * [DIAGNOSTICS_DIR] and to stderr; the CI test script prints them if the test run fails.
 */
object TestDiagnostics {
  val DIAGNOSTICS_DIR: Path = Path.of("build", "hang-diagnostics")

  private val fileTimestampFormat =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSS").withZone(ZoneOffset.UTC)

  /** Returns stack traces of all threads, including lock information. */
  fun threadDump(): String {
    val threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)

    // ThreadInfo.toString() truncates stack traces, so format them ourselves.
    return buildString {
      threads.forEach { info ->
        append("\"${info.threadName}\" #${info.threadId} ${info.threadState}")
        info.lockName?.let { append(" on $it") }
        info.lockOwnerName?.let { append(" owned by \"$it\" #${info.lockOwnerId}") }
        append('\n')

        info.stackTrace.forEachIndexed { index, frame ->
          append("\tat $frame\n")
          info.lockedMonitors
              .filter { it.lockedStackDepth == index }
              .forEach { append("\t- locked $it\n") }
        }

        if (info.lockedSynchronizers.isNotEmpty()) {
          append("\tLocked synchronizers:\n")
          info.lockedSynchronizers.forEach { append("\t- $it\n") }
        }

        append('\n')
      }
    }
  }

  /**
   * Returns the state of the Docker daemon and, if a container ID is supplied, the details and
   * recent log output of that container. Uses the Docker CLI rather than the Testcontainers client
   * so it still works if the Testcontainers client is stuck.
   */
  fun dockerDiagnostics(containerId: String? = null): String {
    val commands =
        listOfNotNull(
            listOf("docker", "ps", "-a"),
            containerId?.let { listOf("docker", "inspect", it) },
            containerId?.let { listOf("docker", "logs", "--timestamps", "--tail", "200", it) },
        )

    return commands.joinToString("\n") { command ->
      "$ ${command.joinToString(" ")}\n${run(command)}"
    }
  }

  /** Writes a diagnostic report to a file and to stderr. Returns the path of the file. */
  fun writeReport(name: String, report: String): Path {
    val text = "===== $name at ${Instant.now()} =====\n$report\n"
    val path = DIAGNOSTICS_DIR.resolve("$name-${fileTimestampFormat.format(Instant.now())}.txt")

    System.err.print(text)

    Files.createDirectories(DIAGNOSTICS_DIR)
    Files.writeString(path, text)

    return path.toAbsolutePath()
  }

  private fun run(command: List<String>): String {
    return try {
      val outputFile = Files.createTempFile("test-diagnostics", ".txt")
      try {
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start()
        val exited = process.waitFor(30, TimeUnit.SECONDS)
        if (!exited) {
          process.destroyForcibly()
        }

        val output = Files.readString(outputFile)
        if (exited) output else "$output\n(Timed out after 30 seconds)\n"
      } finally {
        Files.deleteIfExists(outputFile)
      }
    } catch (e: Exception) {
      "Failed to run command: $e\n"
    }
  }
}
