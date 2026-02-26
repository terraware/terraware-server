package com.terraformation.backend.config

import jakarta.validation.constraints.NotNull
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.springframework.boot.autoconfigure.mail.MailProperties
import org.springframework.boot.context.properties.ConfigurationProperties
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
     * Prefix to use for S3 keys. This can be used when multiple environments are sharing a single
     * S3 bucket and want to avoid name collisions.
     */
    val s3KeyPrefix: String? = null,

    /**
     * Directory to use for local photo storage. If not specified, photos won't be stored on the
     * local filesystem. The server will attempt to create this directory if it doesn't exist.
     */
    val photoDir: Path? = null,

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

    /**
     * Configures the server's email-sending behavior. This is just the application-level
     * configuration. To configure the mail server, use the Spring [MailProperties] config options
     * under `spring.mail`.
     */
    @NotNull val email: EmailConfig = EmailConfig(),

    /** Configures how the server works with GBIF species data. */
    @NotNull val gbif: GbifConfig = GbifConfig(),

    /** Configures options related to accelerator features. */
    val accelerator: AcceleratorConfig = AcceleratorConfig(),

    /** Configures how the server interacts with Atlassian. */
    val atlassian: AtlassianConfig = AtlassianConfig(),

    /** Configures how the server interacts with the Balena cloud service to manage sensor kits. */
    val balena: BalenaConfig = BalenaConfig(),

    /**
     * Configures how the server interacts with the ConvertAPI cloud service to work with file
     * formats we don't support natively.
     */
    val convertApi: ConvertApiConfig = ConvertApiConfig(),

    /** Configures how the server interacts with Dropbox. */
    val dropbox: DropboxConfig = DropboxConfig(),

    /** Configures how terraware-server interacts with GeoServer. */
    val geoServer: GeoServerConfig = GeoServerConfig(),

    /** Configures how the server interacts with HubSpot. */
    val hubSpot: HubSpotConfig = HubSpotConfig(),

    /** Configures how the server interacts with the Mapbox service. */
    val mapbox: MapboxConfig = MapboxConfig(),

    /** Configures how the server interacts with the Mux (video streaming) service. */
    val mux: MuxConfig = MuxConfig(),

    /** Configures notifications processing. */
    val notifications: NotificationsConfig = NotificationsConfig(),

    /** Configures detailed request logging. */
    val requestLog: RequestLogConfig = RequestLogConfig(),
    val report: ReportConfig = ReportConfig(),

    /** Configures the 3D Gaussian splatting service. */
    val splatter: SplatterConfig = SplatterConfig(),

    /** Terraware support email config */
    val support: SupportConfig = SupportConfig(),
) {
  class AcceleratorConfig(
      /** File ID of parent folder to use when creating new folders for application documents. */
      val applicationGoogleFolderId: String? = null,
  )

  class AtlassianConfig(
      /** Atlassian account name */
      val account: String? = null,

      /** Atlassian host endpoint for Terraformation */
      val apiHost: String? = null,

      /** Atlassian api key */
      val apiToken: String? = null,

      /** Enabled flag */
      @DefaultValue("false") val enabled: Boolean = false,

      /** Service Desk Key */
      val serviceDeskKey: String? = null,
  ) {
    init {
      if (enabled) {
        if (account == null || apiHost == null || apiToken == null || serviceDeskKey == null) {
          throw IllegalArgumentException(
              "Account, API host, API token and Jira Service Desk tag are required if Atlassian is enabled"
          )
        }
      }
    }
  }

  class DailyTasksConfig(
      /** Whether to run daily tasks. */
      val enabled: Boolean = true,

      /**
       * What time of day the daily tasks are run. For server-wide tasks, the globally-configured
       * server time zone ([timeZone]) is used. For tasks related to a particular facility,
       * organization, etc., that entity's time zone is used. Default is 00:01 (1 minute past
       * midnight).
       */
      @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) val startTime: LocalTime = LocalTime.of(0, 1),
  )

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
  )

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
              "overrideEmailAddress is required because alwaysSendToOverrideAddress is true"
          )
        }

        if (senderAddress == null) {
          throw IllegalArgumentException("senderAddress is required if email is enabled")
        }
      }
    }
  }

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

  class GeoServerConfig(
      val password: String? = null,
      val username: String? = null,
      /**
       * URL of the WFS API endpoint. This will look something like
       * `https://server-hostname/geoserver/wfs`.
       */
      val wfsUrl: URI? = null,
  )

  class BalenaConfig(
      val apiKey: String? = null,
      @DefaultValue("false") val enabled: Boolean = false,
      val fleetIds: List<Long>? = null,
      /**
       * How frequently to poll for device updates. Value must be in
       * [ISO 8601 duration format](https://en.wikipedia.org/wiki/ISO_8601#Durations).
       */
      @DefaultValue("PT1M") val pollInterval: Duration = Duration.ofMinutes(1),
      @DefaultValue(BALENA_API_URL) val url: URI = URI(BALENA_API_URL),
  ) {
    init {
      if (enabled) {
        if (apiKey == null || fleetIds.isNullOrEmpty()) {
          throw IllegalArgumentException("API key and fleet ID are required if Balena is enabled")
        }
        if (pollInterval <= Duration.ZERO) {
          throw IllegalArgumentException("Poll interval must be greater than 0")
        }
      }
    }

    companion object {
      const val BALENA_API_URL = "https://api.balena-cloud.com"
    }
  }

  class ConvertApiConfig(
      val apiToken: String? = null,
      @DefaultValue("false") val enabled: Boolean = false,
  ) {
    init {
      if (enabled) {
        if (apiToken == null) {
          throw IllegalArgumentException("API token is required if ConvertAPI is enabled")
        }
      }
    }
  }

  class DropboxConfig(
      val appKey: String? = null,
      val appSecret: String? = null,
      @DefaultValue("terraware-server") val clientId: String? = "terraware-server",
      @DefaultValue("false") val enabled: Boolean = false,
      val refreshToken: String? = null,
  ) {
    init {
      if (enabled) {
        if (appKey == null || appSecret == null || refreshToken == null) {
          throw IllegalArgumentException(
              "App key, app secret, and refresh token are required if Dropbox is enabled"
          )
        }
      }
    }
  }

  class HubSpotConfig(
      val clientId: String? = null,
      val clientSecret: String? = null,
      @DefaultValue("false") val enabled: Boolean = false,
  ) {
    init {
      if (enabled) {
        if (clientId == null || clientSecret == null) {
          throw IllegalArgumentException(
              "Client ID and client secret are required if HubSpot is enabled"
          )
        }
      }
    }
  }

  class MapboxConfig(
      val apiToken: String? = null,
      /** How long temporary Mapbox API tokens should last. */
      @DefaultValue("30") val temporaryTokenExpirationMinutes: Long = 30,
  )

  class NotificationsConfig(
      /**
       * The number of days to keep notifications around before deleting them, if deletion is
       * enabled.
       */
      @DefaultValue("30") val retentionDays: Long = 30,
  )

  class MuxConfig(
      @DefaultValue("false") val enabled: Boolean = false,
      /**
       * If set, use this instead of webAppUrl when telling Mux how to fetch assets from us. Primary
       * use case is for dev environments, where webAppUrl points to localhost and this points to
       * something like ngrok.
       */
      val externalUrl: URI? = null,
      val signingKeyId: String? = null,
      val signingKeyPrivate: String? = null,
      @DefaultValue("3600") val streamExpirationSeconds: Long = 3600,
      val tokenId: String? = null,
      val tokenSecret: String? = null,
      /**
       * If true, tell Mux to create video assets in test mode. Test videos are limited to 10
       * seconds, expire after 24 hours, and are watermarked, but don't incur any charges.
       */
      val useTestAssets: Boolean = false,
      val webhookSecret: String? = null,
  ) {
    init {
      if (
          enabled &&
              (signingKeyId == null ||
                  signingKeyPrivate == null ||
                  tokenId == null ||
                  tokenSecret == null)
      ) {
        throw IllegalArgumentException("Token and signing key are required for Mux")
      }
    }
  }

  class SplatterConfig(
      val enabled: Boolean = false,
      /**
       * URL of SQS queue to send outgoing requests to. This should be the queue that the splatter
       * service is listening to.
       */
      val requestQueueUrl: URI? = null,
      /**
       * URL of SQS queue where we will receive responses from the splatter service. This queue
       * should not be shared across environments; each environment should have its own queue.
       */
      val responseQueueUrl: URI? = null,
  ) {
    init {
      if (enabled && (requestQueueUrl == null || responseQueueUrl == null)) {
        throw IllegalArgumentException("Request and response queue URLs are required for Splatter")
      }
    }
  }

  class SupportConfig(
      /** Support email address to use */
      val email: String? = null,
  )

  class RequestLogConfig(
      /**
       * Log request and response bodies from users whose email addresses match this regular
       * expression. Used for troubleshooting.
       */
      val emailRegex: Regex? = null,

      /**
       * Exclude requests whose paths match this regex from detailed logging. This regex needs to
       * match the whole path, not a substring. For example, it should be `/api/v1/abc/def` rather
       * than `abc/def`. Query string parameters are not included in the match.
       */
      val excludeRegex: Regex? = null,
  )

  class ReportConfig(
      @DefaultValue("false") val exportEnabled: Boolean = false,

      /** Export reports to the drive with this ID. Must be set if [exportEnabled] is true. */
      val googleDriveId: String? = null,

      /**
       * If set, make Google Drive API requests as this user. This is needed in order to allow
       * exporting to shared drives that don't allow access by external users.
       */
      val googleEmail: String? = null,

      /**
       * If set, export reports to this folder in the shared drive. If not set, reports are exported
       * to the shared drive's root directory.
       */
      val googleFolderId: String? = null,

      /**
       * If set, use this JSON object as the Google account credentials. Otherwise, use default
       * credentials as described
       * [here](https://cloud.google.com/docs/authentication/application-default-credentials).
       */
      val googleCredentialsJson: String? = null,
  )

  companion object {
    const val BALENA_ENABLED_PROPERTY = "terraware.balena.enabled"
    const val DAILY_TASKS_ENABLED_PROPERTY = "terraware.daily-tasks.enabled"
    const val NOTIFICATIONS_CLEANUP_ENABLED_PROPERTY = "terraware.notifications.cleanup-enabled"
  }
}
