package com.terraformation.backend.support.atlassian

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.support.atlassian.model.ServiceDeskProjectModel
import com.terraformation.backend.support.atlassian.model.ServiceRequestFieldsModel
import com.terraformation.backend.support.atlassian.model.ServiceRequestTypeModel
import com.terraformation.backend.support.atlassian.request.AtlassianHttpRequest
import com.terraformation.backend.support.atlassian.request.CreateServiceRequestHttpRequest
import com.terraformation.backend.support.atlassian.request.DeleteIssueHttpRequest
import com.terraformation.backend.support.atlassian.request.ListServiceDesksHttpRequest
import com.terraformation.backend.support.atlassian.request.ListServiceRequestTypesHttpRequest
import com.terraformation.backend.support.atlassian.request.PostServiceDeskRequestResponse
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
  private val serviceDesk: ServiceDeskProjectModel by lazy { findServiceDesk() }

  val requestTypes: Set<ServiceRequestTypeModel> by lazy { getServiceRequestTypes() }

  fun deleteIssue(issueId: String) {
    requirePermissions { deleteSupportIssue() }
    makeRequest(DeleteIssueHttpRequest(issueId))
  }

  fun createServiceDeskRequest(
      description: String,
      summary: String,
      requestTypeId: Int,
      reporter: String,
  ): PostServiceDeskRequestResponse {
    // No required permission
    if (requestTypes.none { it.id == requestTypeId }) {
      throw IllegalArgumentException("Request ID type not recognized")
    }
    return makeRequest(
        CreateServiceRequestHttpRequest(
            reporter = reporter,
            requestFieldValues = ServiceRequestFieldsModel(summary, description),
            requestTypeId = requestTypeId,
            serviceDeskId = serviceDesk.id,
        ))
  }

  private fun getServiceRequestTypes(): Set<ServiceRequestTypeModel> =
      makeRequest(ListServiceRequestTypesHttpRequest(serviceDesk.id)).values.toSet()

  private fun findServiceDesk(): ServiceDeskProjectModel =
      makeRequest(ListServiceDesksHttpRequest()).values.firstOrNull {
        it.projectKey == config.atlassian.serviceDeskKey
      } ?: throw IllegalStateException("Atlassian configuration is invalid")

  private fun <T> makeRequest(resource: AtlassianHttpRequest<T>): T {
    val response = runBlocking {
      val httpResponse = httpClient.request { resource.buildRequest(this) }
      resource.parseResponse(httpResponse)
    }
    return response
  }

  private fun createHttpClient(): HttpClient {
    if (!config.atlassian.enabled) {
      throw IllegalStateException("Atlassian service is disabled")
    }

    return HttpClient(Java) {
      defaultRequest {
        accept(ContentType.Application.Json)
        contentType(ContentType.Application.Json)
        url(scheme = "https", host = config.atlassian.apiHost!!)
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
