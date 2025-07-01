package com.terraformation.backend.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.log.perClassLogger
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.inject.Named
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.jobrunr.server.BackgroundJobServer
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Restarts JobRunr's background job processing thread after database problems. JobRunr shuts down
 * when it can't talk to the database to avoid spewing errors to the log, but it doesn't come back
 * up when the database becomes available again. We want the server to recover from transient
 * database problems.
 */
@ConditionalOnBean(BackgroundJobServer::class)
@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@Named
class JobRunrRecoveryThread(
    private val backgroundJobServer: BackgroundJobServer,
    private val dataSource: DataSource,
) {
  companion object {
    /** How often to check whether JobRunr's background thread has stopped. */
    private val JOBRUNR_DOWN_POLL_INTERVAL = Duration.ofSeconds(1)!!

    /**
     * How often to check whether the database has become available again after JobRunr's background
     * thread has stopped.
     */
    private val DB_RECOVERY_POLL_INTERVAL = Duration.ofSeconds(10)!!
  }

  private val shutdownLatch = CountDownLatch(1)

  private val log = perClassLogger()

  @PostConstruct
  fun startThread() {
    Thread.ofVirtual().name(javaClass.simpleName).start { run() }
  }

  @PreDestroy
  fun stopThread() {
    shutdownLatch.countDown()
  }

  private fun run() {
    try {
      while (!backgroundJobServer.isRunning &&
          !shutdownLatch.await(JOBRUNR_DOWN_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS)) {
        // Wait for the background thread to start up initially so we don't try to "recover"
        // during application start.
      }

      while (!shutdownLatch.await(JOBRUNR_DOWN_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS)) {
        if (!backgroundJobServer.isRunning) {
          log.warn("JobRunr background thread is not running! Waiting for database to come back")

          while (!backgroundJobServer.isRunning &&
              !shutdownLatch.await(DB_RECOVERY_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS)) {
            try {
              // The connection pool does health checks, so if the database is unavailable, it won't
              // give us a connection and this will throw an exception.
              dataSource.connection.close()

              log.info("Database has recovered; restarting JobRunr")
              backgroundJobServer.start()
            } catch (e: SQLException) {
              // Nothing to do but wait a bit and try again.
            }
          }
        }
      }
    } catch (e: Exception) {
      log.error("JobRunr recovery thread aborting due to unexpected error", e)
    }
  }
}
