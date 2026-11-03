package com.terraformation.backend.db

import com.terraformation.backend.TestDiagnostics
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.support.TestPropertySourceUtils
import org.testcontainers.containers.Network
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * The PostgreSQL Docker container that database-backed tests run against. All the tests in a test
 * run share the same container.
 */
object TestDatabaseContainer {
  private val imageName: DockerImageName =
      DockerImageName.parse("$POSTGRES_DOCKER_REPOSITORY:$POSTGRES_DOCKER_TAG")
          .asCompatibleSubstituteFor("postgres")

  /**
   * How long to wait for the database container to start. Startup normally takes a few seconds;
   * this is long enough to accommodate a slow Docker host while still failing well before a CI job
   * would time out.
   */
  private val postgresStartupTimeout: Duration =
      System.getProperty("terraware.test.postgresStartupTimeout")?.let { Duration.parse(it) }
          ?: Duration.ofMinutes(2)
  private const val POSTGRES_START_ATTEMPTS = 2

  private val log = LoggerFactory.getLogger(TestDatabaseContainer::class.java)

  /** The running container. Only valid once [start] has been called. */
  @Volatile
  lateinit var container: PostgreSQLContainer
    private set

  private fun newPostgresContainer(): PostgreSQLContainer =
      PostgreSQLContainer(imageName)
          .withDatabaseName("terraware")
          .withExposedPorts(PostgreSQLContainer.POSTGRESQL_PORT)
          .withNetwork(Network.newNetwork())
          .withNetworkAliases("postgres")

  /**
   * Starts the database container if it isn't already running. Tests run in parallel, so this may
   * be called concurrently by multiple threads.
   *
   * Testcontainers can occasionally get stuck while starting a container. If startup doesn't finish
   * within [postgresStartupTimeout], writes a diagnostic report, abandons the container, and tries
   * again with a new one.
   */
  @Synchronized
  fun start() {
    if (::container.isInitialized) {
      return
    }

    val reportPaths = mutableListOf<Path>()

    for (attempt in 1..POSTGRES_START_ATTEMPTS) {
      val container = newPostgresContainer()
      val startResult = CompletableFuture<Unit>()
      thread(isDaemon = true, name = "postgres-container-start-$attempt") {
        try {
          container.start()
          startResult.complete(Unit)
        } catch (e: Throwable) {
          startResult.completeExceptionally(e)
        }
      }

      try {
        startResult.get(postgresStartupTimeout.toMillis(), TimeUnit.MILLISECONDS)
        this.container = container
        return
      } catch (e: ExecutionException) {
        throw e.cause ?: e
      } catch (_: TimeoutException) {
        val path =
            TestDiagnostics.writeReport(
                "postgres-startup",
                "Database container startup attempt $attempt of $POSTGRES_START_ATTEMPTS " +
                    "did not finish within $postgresStartupTimeout\n" +
                    "Container ID: ${container.containerId}\n\n" +
                    TestDiagnostics.dockerDiagnostics(container.containerId) +
                    "\n" +
                    TestDiagnostics.threadDump(),
            )
        reportPaths.add(path)
        log.error("Database container startup timed out; diagnostics written to $path")

        thread(isDaemon = true, name = "postgres-container-stop-$attempt") {
          try {
            container.stop()
            container.network?.close()
          } catch (e: Exception) {
            log.warn("Unable to stop abandoned database container", e)
          }
        }
      }
    }

    throw IllegalStateException(
        "Database container did not start after $POSTGRES_START_ATTEMPTS attempts; " +
            "diagnostics are in ${reportPaths.joinToString()}"
    )
  }

  /**
   * Starts the database container if needed, and configures the application's data source to
   * connect to it.
   */
  class DataSourceInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
      start()

      // Run tests using the AWS JDBC wrapper since we'll be using the wrapper in production.
      // The wrapper requires an extra element in the JDBC URL.
      val awsWrapperJdbcUrl = container.jdbcUrl.replace(Regex("^jdbc:"), "jdbc:aws-wrapper:")

      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
          applicationContext,
          "spring.datasource.url=$awsWrapperJdbcUrl",
          "spring.datasource.username=${container.username}",
          "spring.datasource.password=${container.password}",
      )
    }
  }
}
