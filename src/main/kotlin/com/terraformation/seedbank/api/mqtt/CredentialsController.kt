package com.terraformation.seedbank.api.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraformation.seedbank.api.NotFoundException
import com.terraformation.seedbank.api.annotation.DeviceManagerAppEndpoint
import com.terraformation.seedbank.auth.ClientIdentity
import com.terraformation.seedbank.db.tables.daos.OrganizationDao
import com.terraformation.seedbank.mqtt.JwtGenerator
import com.terraformation.seedbank.services.perClassLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController

/** Generates authentication tokens that may be used to authenticate to Mosquitto. */
@DeviceManagerAppEndpoint
@RestController
@RequestMapping("/api/v1/mqtt/credentials")
class CredentialsController(
    private val jwtGenerator: JwtGenerator,
    private val objectMapper: ObjectMapper,
    private val organizationDao: OrganizationDao,
) {
  private val log = perClassLogger()

  /**
   * Returns a JSON Web Token that may be supplied as the password when authenticating with the MQTT
   * broker. The token is valid for 2 minutes, but may be used to establish a persistent connection
   * to the broker.
   */
  @ApiResponse(
      responseCode = "200",
      content =
          [
              Content(
                  mediaType = MediaType.APPLICATION_JSON_VALUE,
                  schema = Schema(implementation = MqttCredentialsResponse::class)),
              Content(
                  mediaType = MediaType.TEXT_PLAIN_VALUE,
                  schema = Schema(implementation = MqttCredentialsResponse::class)),
          ])
  @Operation(
      summary = "Get credentials for the MQTT broker.",
      description =
          "The password is a JSON Web Token which is valid for 2 minutes, but may be used to " +
              "establish a persistent connection to the broker.")
  @PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE])
  @ResponseBody
  fun credentials(@AuthenticationPrincipal identity: ClientIdentity): String {
    val organizationId = identity.organizationId

    val topicPattern =
        if (organizationId != null) {
          val organization =
              organizationDao.fetchOneById(organizationId)
                  ?: throw RuntimeException(
                      "BUG! Organization $organizationId not found but as assigned to client")
          organization.name + "/#"
        } else {
          "#"
        }

    val serializedToken =
        try {
          jwtGenerator.generateMqttToken(identity.username, topicPattern)
        } catch (e: IllegalArgumentException) {
          log.error("Unable to generate MQTT token: $e")
          throw NotFoundException()
        }

    // Explicitly convert to JSON and return a string, rather than letting Spring do the JSON
    // conversion, because rhizo-client asks for a text/plain response which won't trigger automatic
    // JSON rendering.
    val response = MqttCredentialsResponse(identity.username, serializedToken)
    return objectMapper.writeValueAsString(response)
  }
}

data class MqttCredentialsResponse(val username: String, val password: String)
