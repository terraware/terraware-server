package com.terraformation.backend.config

import java.net.URI
import java.nio.file.Path
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.validation.constraints.Min
import javax.validation.constraints.NotNull
import org.springframework.boot.autoconfigure.mail.MailProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.boot.context.properties.bind.DefaultValue
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
    /**
     * URL of the web application for this server. This is used when the server needs to generate a
     * link for a user to follow, e.g., in invitation email messages.
     */
    val webAppUrl: URI,

    /**
     * URL that the server will redirect to when a request returns an error response and indicates
     * that it wants HTML responses. (In other words, when a user hits an endpoint directly in the
     * browser.) Default is `/error` on the web app. The redirect will include a query string with a
     * `message` parameter whose value is a human-readable error message to display.
     */
    val htmlErrorUrl: URI = webAppUrl.resolve("/error"),

    /**
     * Name of S3 bucket to use for storage of files such as photos. If not specified, files won't
     * be stored on S3.
     */
    val s3BucketName: String? = null,

    /**
     * Directory to use for local photo storage. If not specified, photos won't be stored on the
     * local filesystem. The server will attempt to create this directory if it doesn't exist.
     */
    val photoDir: Path? = null,

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

    /**
     * Make the placeholder administration UI available to all users. Default is false, which
     * requires users to already be an admin or owner of at least one organization in order to
     * access the UI.
     */
    val allowAdminUiForNonAdmins: Boolean = false,

    /** Configures execution of daily tasks. */
    val dailyTasks: DailyTasksConfig = DailyTasksConfig(),

    /**
     * Configures the server's communication with the Keycloak authentication server, which manages
     * the login and registration process and is the source of truth for user identities.
     */
    @NotNull val keycloak: KeycloakConfig,

    /**
     * Configures the server's email-sending behavior. This is just the application-level
     * configuration. To configure the mail server, use the Spring [MailProperties] config options
     * under `spring.mail`.
     */
    @NotNull val email: EmailConfig = EmailConfig(),

    /** Configures how the server works with GBIF species data. */
    @NotNull val gbif: GbifConfig = GbifConfig(),

    /** Configures how the server interacts with the Balena cloud service to manage sensor kits. */
    val balena: BalenaConfig = BalenaConfig(),
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
       * Client ID that terraware-server will use for Keycloak admin API requests. Typically, you'll
       * configure a dedicated client ID for this, enable the client's service account, and grant
       * the service account all the roles related to user administration.
       *
       * Defaults to the client ID configured in the Keycloak adapter config.
       *
       * This may be a different client ID than the one that users use to log into the app; the
       * authentication client ID is configured in the Keycloak properties.
       */
      val clientId: String? = null,

      /**
       * Client secret corresponding to clientId.
       *
       * Defaults to the client secret configured in the Keycloak adapter config.
       */
      val clientSecret: String? = null,

      /**
       * Client ID that API clients will use to request access tokens given an offline refresh
       * token. This should be a public client (no client secret) with `offline_access` scope.
       */
      @NotNull val apiClientId: String,

      /** Name of Keycloak group to add API clients to on creation. */
      @DefaultValue("/api-clients") @NotNull val apiClientGroupName: String,

      /**
       * Prefix to put at the beginning of auto-generated Keycloak usernames for API client users.
       * The prefix will cause API client users to be grouped together in the Keycloak admin UI.
       */
      @DefaultValue("api-") @NotNull val apiClientUsernamePrefix: String,

      /**
       * URL to redirect user to after they set their password on initial account creation via the
       * admin UI. Default is to use the request URL of the admin UI but with a path of `/`.
       */
      val postCreateRedirectUrl: URI? = null,
  )

  @ConstructorBinding
  class EmailConfig(
      /**
       * If true, send email messages to recipients. If false, log the message contents but don't
       * actually send email.
       */
      @DefaultValue("false") val enabled: Boolean = false,

      /**
       * If true, always send outgoing email to [overrideAddress], and require that config option to
       * be set. This will usually be true in dev environments.
       */
      @DefaultValue("true") val alwaysSendToOverrideAddress: Boolean = true,

      /**
       * Send all outgoing email to this email address. This is mandatory if
       * [alwaysSendToOverrideAddress] is true.
       */
      val overrideAddress: String? = null,

      /**
       * Sender address to use on outgoing messages. This will be parsed as a full address and can
       * include both email and personal name parts. Required if [enabled] is true.
       */
      val senderAddress: String? = null,

      /** If set, include this prefix in the subject line of every outgoing email message. */
      val subjectPrefix: String? = null,

      /** If true, use SES API rather than SMTP to send email. */
      val useSes: Boolean = false,
  ) {
    init {
      if (enabled) {
        if (alwaysSendToOverrideAddress && overrideAddress == null) {
          throw IllegalArgumentException(
              "overrideEmailAddress is required because alwaysSendToOverrideAddress is true")
        }

        if (senderAddress == null) {
          throw IllegalArgumentException("senderAddress is required if email is enabled")
        }
      }
    }
  }

  @ConstructorBinding
  class GbifConfig(
      /**
       * When importing GBIF taxonomy data, only include taxon entries from these datasets. These
       * should be dataset identifiers (typically UUIDs), not names.
       */
      val datasetIds: List<String>? = null,

      /**
       * When importing GBIF taxonomy data, only include distribution entries from these sources.
       * These should be source names, not dataset identifiers.
       */
      val distributionSources: List<String>? = null,
  )

  @ConstructorBinding
  class BalenaConfig(
      val apiKey: String? = null,
      @DefaultValue("false") val enabled: Boolean = false,
      val fleetId: Long? = null,
      @DefaultValue(BALENA_API_URL) val url: URI = URI(BALENA_API_URL),
  ) {
    init {
      if (enabled) {
        if (apiKey == null || fleetId == null) {
          throw IllegalArgumentException("API key and fleet name are required if Balena is enabled")
        }
      }
    }

    companion object {
      const val BALENA_API_URL = "https://api.balena-cloud.com"
    }
  }

  companion object {
    const val BALENA_ENABLED_PROPERTY = "terraware.balena.enabled"
    const val DAILY_TASKS_ENABLED_PROPERTY = "terraware.daily-tasks.enabled"
  }
}
