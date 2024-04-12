package com.terraformation.backend.support.atlassian

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.support.atlassian.resource.AtlassianResource
import com.terraformation.backend.support.atlassian.resource.CreateServiceDeskRequest
import com.terraformation.backend.support.atlassian.resource.DeleteIssue
import com.terraformation.backend.support.atlassian.resource.PostServiceDeskRequestResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.request
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.jackson.JacksonConverter
import jakarta.inject.Named
import kotlinx.coroutines.runBlocking

/** Submits API requests to interact with Atlassian services. */
@Named
class AtlassianHttpClient(private val config: TerrawareServerConfig) {
  private val httpClient: HttpClient by lazy { createHttpClient() }

  fun deleteIssue(issueId: String) = makeRequest(DeleteIssue(issueId))

  fun createServiceDeskRequest(
      description: String,
      summary: String,
      reporter: String,
      requestType: SupportRequestType,
  ): PostServiceDeskRequestResponse =
      makeRequest(
          CreateServiceDeskRequest(
              description = description,
              summary = summary,
              reporter = reporter,
              requestTypeId = getSupportRequestTypeId(requestType),
              serviceDeskId = config.atlassian.serviceDeskId!!,
          ))

  private fun <T> makeRequest(resource: AtlassianResource<T>): T {

    val response = runBlocking {
      val httpResponse = httpClient.request { resource.buildRequest(this) }
      resource.parseResponse(httpResponse)
    }

    return response
  }

  private fun getSupportRequestTypeId(requestType: SupportRequestType) =
      when (requestType) {
        SupportRequestType.BUG_REPORT -> config.atlassian.bugReportTypeId!!
        SupportRequestType.FEATURE_REQUEST -> config.atlassian.featureRequestTypeId!!
      }

  private fun createHttpClient(): HttpClient {
    return HttpClient(Java) {
      defaultRequest {
        accept(ContentType.Application.Json)
        contentType(ContentType.Application.Json)
        url(scheme = "https", host = config.atlassian.apiHostname!!)
      }

      install(ContentNegotiation) { register(ContentType.Application.Json, JacksonConverter()) }

      // By default, treat non-2xx responses as errors. This can be overridden per request.
      expectSuccess = true
      install(Auth) {
        basic {
          credentials {
            BasicAuthCredentials(
                username = config.atlassian.account!!, password = config.atlassian.apiToken!!)
          }
          sendWithoutRequest { true }
        }
      }
    }
  }
}
