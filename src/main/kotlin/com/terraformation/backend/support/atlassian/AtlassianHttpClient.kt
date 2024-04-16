package com.terraformation.backend.support.atlassian

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.support.atlassian.model.ServiceDeskModel
import com.terraformation.backend.support.atlassian.model.ServiceRequestFieldsModel
import com.terraformation.backend.support.atlassian.model.ServiceRequestTypeModel
import com.terraformation.backend.support.atlassian.resource.AtlassianResource
import com.terraformation.backend.support.atlassian.resource.CreateServiceDeskRequest
import com.terraformation.backend.support.atlassian.resource.DeleteIssue
import com.terraformation.backend.support.atlassian.resource.ListServiceDesks
import com.terraformation.backend.support.atlassian.resource.ListServiceRequestTypes
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
  private val serviceDesk: ServiceDeskModel? by lazy { findServiceDesk() }

  val requestTypes: Set<ServiceRequestTypeModel> by lazy { getServiceRequestTypes() }

  fun deleteIssue(issueId: String) {
    requirePermissions { deleteSupportIssue() }
    makeRequest(DeleteIssue(issueId))
  }

  fun createServiceDeskRequest(
      description: String,
      summary: String,
      requestTypeId: Int,
      reporter: String,
  ): PostServiceDeskRequestResponse {
    // No required permission
    if (requestTypes.find { it.id == requestTypeId } == null) {
      throw RuntimeException("Request ID type not recognized")
    }
    return makeRequest(
        CreateServiceDeskRequest(
            reporter = reporter,
            requestFieldValues = ServiceRequestFieldsModel(summary, description),
            requestTypeId = requestTypeId,
            serviceDeskId = serviceDeskId(),
        ))
  }

  private fun serviceDeskId(): Int =
      if (serviceDesk == null) {
        throw IllegalStateException("Atlassian configuration is invalid")
      } else {
        serviceDesk!!.id
      }

  private fun getServiceRequestTypes(): Set<ServiceRequestTypeModel> =
      makeRequest(ListServiceRequestTypes(serviceDeskId())).values.toSet()

  private fun findServiceDesk(): ServiceDeskModel? =
      makeRequest(ListServiceDesks()).values.firstOrNull {
        it.projectKey == config.atlassian.serviceDeskKey
      }

  private fun <T> makeRequest(resource: AtlassianResource<T>): T {
    if (!config.atlassian.enabled) {
      throw IllegalStateException("Atlassian service is disabled")
    }

    val response = runBlocking {
      val httpResponse = httpClient.request { resource.buildRequest(this) }
      resource.parseResponse(httpResponse)
    }
    return response
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
