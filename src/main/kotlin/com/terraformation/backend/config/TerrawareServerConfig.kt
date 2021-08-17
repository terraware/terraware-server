package com.terraformation.backend.config

import java.net.URI
import java.nio.file.Path
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.validation.constraints.Min
import javax.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.core.io.Resource
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.validation.annotation.Validated

/**
 * Application-specific configuration options. These are populated by Spring's configuration
 * properties system from a few sources, the most relevant ones being `application.yaml` files and
 * environment variables. In the YAML files, these are all under a top-level `terraware` key. In the
 * environment, these are all pulled from environment variables with a `TERRAWARE_` prefix in their
 * names.
 */
@ConfigurationProperties("terraware")
@ConstructorBinding
@Validated
class TerrawareServerConfig(
    /** URL of site-specific configuration file. */
    @NotNull val siteConfigUrl: Resource,

    /**
     * How often to refresh site-specific configuration, in seconds. 0 disables periodic refresh.
     */
    @Min(0) val siteConfigRefreshSecs: Long = 3600,

    /**
     * Directory to use for photo storage. The server will attempt to create this directory if it
     * doesn't exist.
     */
    @NotNull val photoDir: Path,

    /**
     * Number of levels of parent directories to create for photos. Photos are stored in a tree of
     * single-character subdirectories from the beginning of the accession number. For example, if
     * this is 3, and `photoDir` is `/x/y`, photos for accession `ABCDEFG` will be stored in
     * `/x/y/A/B/C/ABCDEFG`.
     */
    @Min(0) val photoIntermediateDepth: Int = 3,

    /**
     * Server's time zone. This is mostly used to determine when scheduled daily jobs are run.
     * Default is UTC. May be specified as a tz database time zone name such as `US/Hawaii` or a UTC
     * offset such as `+04:00`.
     */
    val timeZone: ZoneId = ZoneOffset.UTC,

    /**
     * Use a fake clock that can be advanced via API requests. This should only be enabled in test
     * environments.
     */
    val useTestClock: Boolean = false,

    /** Configures execution of daily tasks. */
    val dailyTasks: DailyTasksConfig = DailyTasksConfig(),

    /**
     * Configures the server's communication with the Keycloak authentication server, which manages
     * the login and registration process and is the source of truth for user identities.
     */
    @NotNull val keycloak: KeycloakConfig,
) {
  @ConstructorBinding
  class DailyTasksConfig(
      /** Whether to run daily tasks. */
      val enabled: Boolean = true,

      /**
       * What time of day the daily tasks are run. This is treated as a local time in the configured
       * [timeZone]. Default is 8AM.
       */
      @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) val startTime: LocalTime = LocalTime.of(8, 0)
  )

  @ConstructorBinding
  class KeycloakConfig(
      /**
       * URL of the root of the Keycloak server's administrative API. Typically, this will be
       * `https://server.domain.name/auth`.
       */
      @NotNull val serverUrl: URI,

      /** Keycloak realm containing user information. */
      @NotNull val realm: String,

      /**
       * Client ID that terraware-server will use for Keycloak admin API requests. Typically, you'll
       * configure a dedicated client ID for this, enable the client's service account, and grant
       * the service account all the roles related to user administration.
       */
      @NotNull val clientId: String,

      /** Client secret corresponding to clientId. */
      @NotNull val clientSecret: String,
  )

  companion object {
    const val DAILY_TASKS_ENABLED_PROPERTY = "terraware.daily-tasks.enabled"
  }
}
