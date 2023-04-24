package com.terraformation.backend.tracking.mapbox

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.log.perClassLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Named
import kotlinx.coroutines.runBlocking

@Named
class MapboxService(
    private val config: TerrawareServerConfig,
    private val httpClient: HttpClient,
) {
  private val log = perClassLogger()

  val enabled: Boolean = config.mapbox.apiToken?.isNotBlank() == true

  /**
   * The Mapbox username of the account that owns the configured API token. Some Mapbox APIs require
   * the username to be included explicitly in the request.
   *
   * We fetch this lazily the first time it's accessed by requesting information about our access
   * token, which includes the username.
   */
  private val username: String by lazy {
    runBlocking {
      try {
        val response: RetrieveTokenResponsePayload = httpClient.get(mapboxUrl("tokens/v2")).body()
        response.token.user
      } catch (e: ClientRequestException) {
        log.error("HTTP ${e.response.status} when fetching Mapbox token information")
        log.info("Mapbox error response payload: ${e.response.bodyAsText()}")
        throw MapboxRequestFailedException(e.response.status)
      }
    }
  }

  /** Generates a temporary API token. */
  fun generateTemporaryToken(): String {
    if (!enabled) {
      throw MapboxNotConfiguredException()
    }

    val url = mapboxUrl("tokens/v2/$username")

    val expirationTime =
        Instant.now().plus(config.mapbox.temporaryTokenExpirationMinutes, ChronoUnit.MINUTES)
    val requestPayload =
        TemporaryTokenRequestPayload(
            expires = expirationTime, scopes = listOf("styles:tiles", "styles:read", "fonts:read"))

    return runBlocking {
      try {
        val responsePayload: TemporaryTokenResponsePayload =
            httpClient.post(url) { setBody(requestPayload) }.body()

        responsePayload.token
      } catch (e: ClientRequestException) {
        log.error("Mapbox token request failed with HTTP ${e.response.status}")
        log.info("Mapbox error response payload: ${e.response.bodyAsText()}")
        throw MapboxRequestFailedException(e.response.status)
      }
    }
  }

  private fun mapboxUrl(endpoint: String): URL =
      URI("https://api.mapbox.com/$endpoint?access_token=${config.mapbox.apiToken}").toURL()

  data class TemporaryTokenRequestPayload(
      val expires: Instant,
      val scopes: List<String>,
  )

  data class TemporaryTokenResponsePayload(val token: String)

  data class RetrieveTokenResponsePayload(val code: String, val token: Token) {
    data class Token(val user: String)
  }
}
