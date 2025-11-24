package com.terraformation.backend.support.atlassian

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.support.atlassian.model.JiraServiceRequestFieldsModel
import com.terraformation.backend.support.atlassian.model.ServiceDeskProjectModel
import com.terraformation.backend.support.atlassian.model.SupportRequestType
import com.terraformation.backend.support.atlassian.request.AtlassianHttpRequest
import com.terraformation.backend.support.atlassian.request.AttachTemporaryFileResponse
import com.terraformation.backend.support.atlassian.request.AttachTemporaryFilesHttpRequest
import com.terraformation.backend.support.atlassian.request.CreateAttachmentsHttpRequest
import com.terraformation.backend.support.atlassian.request.CreateServiceRequestHttpRequest
import com.terraformation.backend.support.atlassian.request.DeleteIssueHttpRequest
import com.terraformation.backend.support.atlassian.request.ListServiceDesksHttpRequest
import com.terraformation.backend.support.atlassian.request.ListServiceRequestTypesHttpRequest
import com.terraformation.backend.support.atlassian.request.PostServiceDeskRequestResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.request
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.jackson3.JacksonConverter
import jakarta.inject.Named
import kotlinx.coroutines.runBlocking

/** Submits API requests to interact with Atlassian services. */
@Named
class AtlassianHttpClient(private val config: TerrawareServerConfig) {
  private val log = perClassLogger()
  private val httpClient: HttpClient by lazy { createHttpClient() }
  private val serviceDesk: ServiceDeskProjectModel by lazy { findServiceDesk() }

  val requestTypeIds: Map<SupportRequestType, Int> by lazy { getJiraServiceRequestTypes() }

  /**
   * i18n error key that's returned when attempting to attach a file to a service request when the
   * file's temporary ID isn't found. Temporary IDs expire after 1 hour (hence the mention of
   * timeouts in the error key) but they also disappear when the files are attached to work items.
   * If a client sends us two "create issue" requests with the same temporary ID, we'll get this
   * error when we're handling the second request since the file will have already been used.
   */
  private val attachmentTimeoutErrorKey = "sd.attachment.temporary.session.time.out.ids"

  fun deleteIssue(issueId: String) {
    requirePermissions { deleteSupportIssue() }
    makeRequest(DeleteIssueHttpRequest(issueId))
  }

  fun createServiceDeskRequest(
      description: String,
      summary: String,
      requestType: SupportRequestType,
      reporter: String,
  ): PostServiceDeskRequestResponse {
    // No required permissions
    val requestTypeId =
        requestTypeIds[requestType]
            ?: throw IllegalArgumentException(
                "Request type does not have a configured Jira support request"
            )

    return makeRequest(
        CreateServiceRequestHttpRequest(
            reporter = reporter,
            requestFieldValues = JiraServiceRequestFieldsModel(summary, description),
            requestTypeId = requestTypeId,
            serviceDeskId = serviceDesk.id,
        )
    )
  }

  fun attachTemporaryFile(
      filename: String,
      sizedInputStream: SizedInputStream,
  ): AttachTemporaryFileResponse {
    // No required permissions

    return makeRequest(
        AttachTemporaryFilesHttpRequest(
            inputStream = sizedInputStream,
            filename = filename,
            contentType = sizedInputStream.contentType?.type,
            fileSize = sizedInputStream.size,
            serviceDeskId = serviceDesk.id,
        )
    )
  }

  fun createAttachments(
      issueId: String,
      attachmentIds: List<String>,
      comment: String? = null,
  ) {
    // No required permissions

    try {
      makeRequest(
          CreateAttachmentsHttpRequest(
              issueId = issueId,
              attachmentIds = attachmentIds,
              comment = comment,
          )
      )
    } catch (e: ClientRequestException) {
      if (e.response.status == HttpStatusCode.BadRequest) {
        try {
          val errorResponse = runBlocking { e.response.body<AtlassianErrorResponse>() }
          if (errorResponse.i18nErrorMessage?.i18nKey == attachmentTimeoutErrorKey) {
            log.error(
                "Jira attachment temporary ID(s) not found. This may be due to a duplicate API " +
                    "request from the web app."
            )
          }
        } catch (e2: NoTransformationFoundException) {
          log.warn("Unable to parse Atlassian API error response", e2)
        }
      }

      throw e
    }
  }

  private fun getJiraServiceRequestTypes(): Map<SupportRequestType, Int> =
      makeRequest(ListServiceRequestTypesHttpRequest(serviceDesk.id))
          .values
          .mapNotNull { model ->
            SupportRequestType.forJsonValue(model.name)?.let { it to model.id }
          }
          .associate { it }

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
      install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }

      // By default, treat non-2xx responses as errors. This can be overridden per request.
      expectSuccess = true
      install(Auth) {
        basic {
          credentials {
            BasicAuthCredentials(
                username = config.atlassian.account!!,
                password = config.atlassian.apiToken!!,
            )
          }
          sendWithoutRequest { true }
        }
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  data class AtlassianI18nErrorMessage(val i18nKey: String?, val parameters: List<String>?)

  @JsonIgnoreProperties(ignoreUnknown = true)
  data class AtlassianErrorResponse(
      val errorMessage: String?,
      val i18nErrorMessage: AtlassianI18nErrorMessage?,
  )
}
