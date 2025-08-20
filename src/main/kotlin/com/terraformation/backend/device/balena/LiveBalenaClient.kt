package com.terraformation.backend.device.balena

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.log.perClassLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import jakarta.inject.Named
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Interacts with the live balena API. This is only used if Balena is enabled in the server
 * configuration.
 */
@ConditionalOnProperty(TerrawareServerConfig.BALENA_ENABLED_PROPERTY, havingValue = "true")
@Named
class LiveBalenaClient(
    private val config: TerrawareServerConfig,
    private val httpClient: HttpClient,
) : BalenaClient {
  private val log = perClassLogger()

  private val apiKey: String
    get() = config.balena.apiKey ?: throw BalenaNotEnabledException()

  private val fleetIds: List<Long>
    get() = config.balena.fleetIds ?: throw BalenaNotEnabledException()

  override fun configureDeviceManager(
      balenaId: BalenaDeviceId,
      facilityId: FacilityId,
      token: String,
      overwrite: Boolean,
  ) {
    val tokenExcerpt = token.substring(0, 8) + "..."
    log.info("Configure Balena device $balenaId with facility $facilityId, token $tokenExcerpt")

    // If the first of these calls succeeds and the second fails, the user can try again because
    // setting a variable to its existing value is permitted even with overwrite=false.
    setDeviceEnvironmentVar(balenaId, FACILITIES_ENV_VAR_NAME, "$facilityId", overwrite)
    setDeviceEnvironmentVar(balenaId, TOKEN_ENV_VAR_NAME, token, overwrite)
  }

  override fun getSensorKitIdForBalenaId(balenaId: BalenaDeviceId): String? {
    val response =
        sendRequest<GetTagsForDeviceResponse>(
            DEVICE_TAG_PATH,
            expand = listOf("device"),
            filter =
                listOf(
                    filterTerm("device/belongs_to__application", fleetIds),
                    filterTerm("device/id", balenaId),
                    filterTerm("tag_key", SENSOR_KIT_ID_TAG_KEY),
                ),
            select = listOf("value"),
        )

    return response.d.firstOrNull()?.value
  }

  override fun listModifiedDevices(after: Instant): List<BalenaDevice> {
    // Balena doesn't consider changes to the overall_progress field to be modifications of the
    // device, so the modified_at timestamp doesn't get updated as installation progresses. Since
    // we want to monitor changes to overall_progress, we need to look for devices with updated
    // modification times OR non-null overall_progress values (overall_progress gets set to null
    // when there is no install happeening) meaning we need to construct a nested filter term.
    val modifiedAtTerm = filterTerm("modified_at", after, "gt")
    val overallProgressTerm = filterTerm("overall_progress", 0, "ge")
    val combinedFilter = "($modifiedAtTerm+or+$overallProgressTerm)"

    val response =
        sendRequest<ListDevicesResponse>(
            DEVICE_PATH,
            filter = listOf(combinedFilter, filterTerm("belongs_to__application", fleetIds)),
            select = BalenaDevice.selectFields,
        )

    return response.d
  }

  fun setDeviceEnvironmentVar(
      balenaId: BalenaDeviceId,
      name: String,
      value: String,
      overwrite: Boolean = false,
  ) {
    // Setting a variable to its existing value is a no-op regardless of the overwrite flag.
    val currentValue = getDeviceEnvironmentVar(balenaId, name)
    if (currentValue == value) {
      log.debug("Balena device $balenaId environment variable $name already set to desired value")
      return
    }

    try {
      sendRequest<Unit>(
          DEVICE_ENV_VAR_PATH,
          body = CreateDeviceEnvVarRequest(balenaId, name, value),
      )

      log.info("Created environment variable $name on Balena device $balenaId")
    } catch (e: BalenaRequestFailedException) {
      when (e.statusCode) {
        HttpStatusCode.Conflict.value ->
            if (overwrite) {
              updateDeviceEnvironmentVar(balenaId, name, value)
            } else {
              throw BalenaVariableExistsException(balenaId, name)
            }
        else -> throw e
      }
    }
  }

  fun getDeviceEnvironmentVar(deviceId: BalenaDeviceId, name: String): String? {
    val response =
        sendRequest<GetDeviceEnvironmentVarValueResponse>(
            DEVICE_ENV_VAR_PATH,
            filter = listOf(filterTerm("device", deviceId), filterTerm("name", name)),
        )

    return response.d.getOrNull(0)?.value
  }

  /**
   * Returns the ID of an environment variable on a specific device. This is needed to update
   * existing variables.
   */
  fun getDeviceEnvironmentVarId(deviceId: BalenaDeviceId, name: String): Long? {
    val response =
        sendRequest<GetDeviceEnvironmentVarIdResponse>(
            DEVICE_ENV_VAR_PATH,
            filter = listOf(filterTerm("device", deviceId), filterTerm("name", name)),
            select = listOf("id"),
        )

    return response.d.getOrNull(0)?.id
  }

  /** Updates the value of an existing environment variable on a device. */
  private fun updateDeviceEnvironmentVar(deviceId: BalenaDeviceId, name: String, value: String) {
    val varId =
        getDeviceEnvironmentVarId(deviceId, name)
            ?: throw BalenaVariableNotFoundException(deviceId, name)

    sendRequest<Unit>(
        "$DEVICE_ENV_VAR_PATH($varId)",
        method = HttpMethod.Patch,
        body = UpdateDeviceEnvVarRequest(value),
    )

    log.info("Updated environment variable $name on Balena device $deviceId")
  }

  /**
   * Returns a search term suitable for concatenating to the "$filter" query string parameter of a
   * Balena API request. Numbers and nulls are rendered directly; other values are converted to
   * strings, URL-encoded, and surrounded with single quotes.
   *
   * Caution: Balena is picky about which characters are URL-encoded and which aren't. Make sure you
   * test this against the actual Balena service if you change the rendering logic.
   */
  private fun filterTerm(field: String, value: Any?, operation: String = "eq"): String {
    if (value is Collection<*>) {
      return "(" + value.joinToString("+or+") { filterTerm(field, it, operation) } + ")"
    }

    val encodedValue =
        when (value) {
          is Number -> value.toString()
          null -> "null"
          else -> "'" + URLEncoder.encode("$value", StandardCharsets.UTF_8) + "'"
        }

    return "$field+$operation+$encodedValue"
  }

  /**
   * Returns a query string with the standard parameters supported by most GET endpoints in the
   * Balena API.
   *
   * @param filter List of filter terms as rendered by [filterTerm]. The terms are ANDed together
   *   (that is, a result has to match all of them to be returned).
   * @param expand List of child object fields whose contents should be included in the results
   *   (default is to just include their IDs).
   * @param select List of fields to include in the results. If not specified, a default set of
   *   fields is returned based on the type of object being queries. (The default set of fields may
   *   be a subset of the available fields, rather than the full set.)
   */
  private fun queryString(
      filter: List<String>? = null,
      expand: List<String>? = null,
      select: List<String>? = null,
  ): String {
    val elements =
        listOfNotNull(
            filter?.joinToString("+and+", prefix = "\$filter="),
            expand?.joinToString(",", prefix = "\$expand="),
            select?.joinToString(",", prefix = "\$select="),
        )

    return if (elements.isNotEmpty()) elements.joinToString("&", prefix = "?") else ""
  }

  private suspend fun doSendRequest(
      path: String,
      filter: List<String>?,
      expand: List<String>?,
      select: List<String>?,
      body: Any? = null,
      httpMethod: HttpMethod,
  ): HttpResponse {
    val url = config.balena.url.resolve(path + queryString(filter, expand, select)).toString()

    return try {
      httpClient.request(url) {
        method = httpMethod
        bearerAuth(apiKey)
        if (body != null) {
          setBody(body)
        }
      }
    } catch (e: ClientRequestException) {
      log.debug("Balena response ${e.response.status}: ${e.response.bodyAsText()}")
      throw BalenaRequestFailedException(e.response.status.value)
    }
  }

  internal inline fun <reified T> sendRequest(
      path: String,
      filter: List<String>? = null,
      expand: List<String>? = null,
      select: List<String>? = null,
      body: Any? = null,
      method: HttpMethod = if (body != null) HttpMethod.Post else HttpMethod.Get,
  ): T {
    return runBlocking {
      val response = doSendRequest(path, filter, expand, select, body, method)
      if (T::class.java == Unit.javaClass) {
        T::class.objectInstance!!
      } else {
        response.body()
      }
    }
  }

  data class CreateDeviceEnvVarRequest(
      val device: BalenaDeviceId,
      val name: String,
      val value: String,
  )

  data class UpdateDeviceEnvVarRequest(val value: String)

  data class GetTagsForDeviceResponse(val d: List<TagValue>) {
    @JsonIgnoreProperties(ignoreUnknown = true) data class TagValue(val value: String)
  }

  data class GetDeviceEnvironmentVarIdResponse(val d: List<IdPayload>) {
    @JsonIgnoreProperties(ignoreUnknown = true) data class IdPayload(var id: Long)
  }

  data class GetDeviceEnvironmentVarValueResponse(val d: List<ValuePayload>) {
    @JsonIgnoreProperties(ignoreUnknown = true) data class ValuePayload(var value: String)
  }

  data class ListDevicesResponse(val d: List<BalenaDevice>)

  companion object {
    const val DEVICE_ENV_VAR_PATH = "/v6/device_environment_variable"
    const val DEVICE_PATH = "/v6/device"
    const val DEVICE_TAG_PATH = "/v6/device_tag"
    const val DEVICE_TYPE_PATH = "/v6/device_type"
    const val FLEET_PATH = "/v6/application"

    const val SENSOR_KIT_ID_TAG_KEY = "sensor_kit_id"

    const val FACILITIES_ENV_VAR_NAME = "FACILITIES"
    const val TOKEN_ENV_VAR_NAME = "OFFLINE_REFRESH_TOKEN"
  }
}
