package com.terraformation.backend.db

import com.terraformation.backend.TestDiagnostics
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.test.context.support.TestPropertySourceUtils
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * The PostgreSQL Docker container that database-backed tests run against. All the tests in a test
 * run share the same container, and on developer machines, the container is also reused across test
 * runs; see [newPostgresContainer].
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

  /**
   * Name of the database the tests run against. This is distinct from the container's default
   * database; see [createTestDatabase]. Only valid once [start] has been called.
   */
  @Volatile
  lateinit var databaseName: String
    private set

  /**
   * Returns a JDBC URL for a database in the container. Only valid once [start] has been called.
   */
  fun jdbcUrl(databaseName: String): String = jdbcUrl(container, databaseName)

  /**
   * Creates a container that's reused across test runs if Testcontainers' container reuse is
   * enabled. The Gradle build enables it for local test runs by setting
   * `TESTCONTAINERS_REUSE_ENABLE`; test runs that don't go through Gradle use the developer's
   * `~/.testcontainers.properties` setting. If reuse isn't enabled, as on CI, Testcontainers
   * creates a new container for each test run and removes it afterwards.
   *
   * The container configuration needs to be identical across runs for Testcontainers to reuse it,
   * so this doesn't attach the container to a network; tests that need other containers to reach
   * the database need to connect it to a network themselves.
   *
   * Testcontainers doesn't clean up reusable containers, and only reuses running ones. So the
   * database shuts itself down after [POSTGRES_IDLE_TIMEOUT_MINUTES] minutes with no client
   * connections (a feature of the `terraware/postgres` image), and Docker removes the container,
   * along with its data volume, once it stops for any reason.
   */
  private fun newPostgresContainer(): PostgreSQLContainer =
      PostgreSQLContainer(imageName)
          .withDatabaseName("terraware")
          .withExposedPorts(PostgreSQLContainer.POSTGRESQL_PORT)
          .withLabel("com.terraformation.test-database", "true")
          .withEnv("IDLE_SHUTDOWN_MINUTES", "$POSTGRES_IDLE_TIMEOUT_MINUTES")
          .withReuse(true)
          .withCreateContainerCmdModifier { it.hostConfig?.withAutoRemove(true) }

  private const val POSTGRES_IDLE_TIMEOUT_MINUTES = 30

  /**
   * Creates the database the tests run against, if it doesn't already exist.
   *
   * A reusable container is shared by every checkout of the code on the developer's machine, and
   * those checkouts can be running tests concurrently and can have different sets of database
   * migrations. So each combination of checkout directory and migration set gets its own database,
   * whose name includes hashes of both. Flyway applies the migrations when a new database is first
   * used, and only validates them afterwards.
   *
   * Databases for older migration sets in the same checkout are dropped, unless they're still in
   * use.
   */
  private fun createTestDatabase(container: PostgreSQLContainer): String {
    val checkoutPrefix = "terraware_${sha256Hex(System.getProperty("user.dir")).take(8)}_"
    val databaseName = checkoutPrefix + migrationsHash().take(12)

    DriverManager.getConnection(
            jdbcUrl(container, "postgres"),
            container.username,
            container.password,
        )
        .use { connection ->
          val existingDatabases =
              connection
                  .prepareStatement("SELECT datname FROM pg_database WHERE starts_with(datname, ?)")
                  .use { statement ->
                    statement.setString(1, checkoutPrefix)
                    statement.executeQuery().use { resultSet ->
                      buildList { while (resultSet.next()) add(resultSet.getString(1)) }
                    }
                  }

          connection.createStatement().use { statement ->
            if (databaseName !in existingDatabases) {
              log.info("Creating test database $databaseName")
              try {
                // The image's init script sets up template_postgis with the same extensions as
                // the container's default database.
                statement.execute("CREATE DATABASE \"$databaseName\" TEMPLATE template_postgis")
              } catch (e: SQLException) {
                if (e.sqlState != DUPLICATE_DATABASE_SQLSTATE) {
                  throw e
                }
              }
            }

            existingDatabases
                .filter { it != databaseName }
                .forEach { staleDatabase ->
                  try {
                    statement.execute("DROP DATABASE \"$staleDatabase\"")
                    log.info("Dropped stale test database $staleDatabase")
                  } catch (e: SQLException) {
                    log.info("Unable to drop stale test database $staleDatabase: ${e.message}")
                  }
                }
          }
        }

    return databaseName
  }

  private const val DUPLICATE_DATABASE_SQLSTATE = "42P04"

  private fun jdbcUrl(container: PostgreSQLContainer, databaseName: String): String =
      "jdbc:postgresql://${container.host}:" +
          "${container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$databaseName"

  /** Computes a hash of the paths and contents of all the database migration scripts. */
  private fun migrationsHash(): String {
    val digest = MessageDigest.getInstance("SHA-256")

    PathMatchingResourcePatternResolver()
        .getResources("classpath*:db/migration/**/*")
        .filter { it.isReadable }
        .map { it.url.toString().substringAfter("db/migration/") to it }
        .sortedBy { it.first }
        .forEach { (path, resource) ->
          digest.update(path.toByteArray())
          digest.update(resource.contentAsByteArray)
        }

    return HexFormat.of().formatHex(digest.digest())
  }

  private fun sha256Hex(value: String): String =
      HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

  /**
   * Starts the database container, or connects to an existing one if container reuse is enabled,
   * and creates the test database if needed. Only does anything the first time it's called in a
   * test run. Tests run in parallel, so this may be called concurrently by multiple threads.
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
        databaseName = createTestDatabase(container)
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
      val awsWrapperJdbcUrl = jdbcUrl(databaseName).replace(Regex("^jdbc:"), "jdbc:aws-wrapper:")

      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
          applicationContext,
          "spring.datasource.url=$awsWrapperJdbcUrl",
          "spring.datasource.username=${container.username}",
          "spring.datasource.password=${container.password}",
      )
    }
  }
}
