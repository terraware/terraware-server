package com.terraformation.backend.gis.geoserver

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.requirePermissions
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.serialization.jackson.JacksonConverter
import jakarta.inject.Named
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.runBlocking
import org.geotools.feature.FeatureCollection
import org.geotools.geojson.feature.FeatureJSON

@Named
class GeoServerClient(
    private val config: TerrawareServerConfig,
    private val objectMapper: ObjectMapper,
) {
  private val wfsVersion = "2.0.0"
  private val httpClient: HttpClient by lazy { createHttpClient() }

  fun describeFeatureType(featureType: String): FeatureTypeDescription {
    val descriptions =
        sendGetRequest<FeatureTypeDescriptions>(
            "DescribeFeatureType",
            mapOf("typeNames" to featureType),
        )
    return descriptions.featureTypes.first().copy(targetPrefix = descriptions.targetPrefix)
  }

  fun getFeatures(
      featureType: String,
      filter: String,
      properties: List<String>?,
      srsName: String? = null,
  ): FeatureCollection<*, *> {
    return FeatureJSON()
        .readFeatureCollection(
            sendGetRequest<String>(
                "GetFeature",
                listOfNotNull(
                        srsName?.let { "srsName" to it },
                        "cql_filter" to filter,
                        "typeNames" to featureType,
                        properties?.let { "properties" to it.joinToString(",") },
                    )
                    .toMap(),
            )
        )
  }

  fun getCapabilities(): WfsCapabilities {
    return sendGetRequest("GetCapabilities")
  }

  suspend fun proxyGetRequest(queryParams: Map<String, Array<String>>): HttpResponse {
    requirePermissions { proxyGeoServerGetRequests() }

    return httpClient.get {
      // Pass GeoServer error responses through to the client.
      expectSuccess = false

      queryParams.forEach { (name, values) -> values.forEach { value -> parameter(name, value) } }
    }
  }

  private inline fun <reified T> sendGetRequest(
      command: String,
      params: Map<String, Any> = emptyMap(),
  ): T {
    return runBlocking { sendRequest(HttpMethod.Get, command, params).body() }
  }

  private suspend fun sendRequest(
      requestMethod: HttpMethod,
      command: String,
      params: Map<String, Any> = emptyMap(),
  ): HttpResponse {
    return httpClient.request {
      method = requestMethod
      parameter("request", command)
      parameter("service", "wfs")
      parameter("version", wfsVersion)
      params.forEach { parameter(it.key, it.value.toString()) }

      // Prefer JSON if supported for the operation; some operations only support XML and
      // will ignore this parameter.
      parameter("outputFormat", MediaType.APPLICATION_JSON)
    }
  }

  private fun createHttpClient(): HttpClient {
    val wfsUrl =
        config.geoServer.wfsUrl ?: throw IllegalStateException("GeoServer URL not configured")

    return HttpClient(Java) {
      defaultRequest { url(wfsUrl.toString()) }

      install(ContentNegotiation) {
        register(ContentType.Application.Json, JacksonConverter(objectMapper))
        register(
            ContentType.Application.Xml,
            JacksonConverter(
                XmlMapper.builder()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                    .defaultUseWrapper(false)
                    .build()
                    .registerKotlinModule()
            ),
        )
      }

      // By default, throw exceptions if we get non-2xx responses.
      expectSuccess = true

      // Use basic authentication if configured; otherwise send anonymous requests.
      if (config.geoServer.username != null) {
        install(Auth) {
          basic {
            credentials {
              BasicAuthCredentials(config.geoServer.username!!, config.geoServer.password ?: "")
            }
          }
        }
      }
    }
  }
}
