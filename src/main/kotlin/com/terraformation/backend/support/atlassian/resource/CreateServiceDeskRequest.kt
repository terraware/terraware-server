package com.terraformation.backend.support.atlassian.resource

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.support.atlassian.SupportRequestType
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.path
import javax.inject.Named

/**
 * The POST request to create a service ticket.
 *
 * @param requestTypeId the ID of the JIRA issue type
 * @param summary the title of the JIRA issue
 * @param description the details of the JIRA issue
 * @param reporter the email address of the reporter
 */
data class CreateServiceDeskRequest(
    val description: String,
    val summary: String,
    val reporter: String? = null,
    val requestTypeId: Int,
    val serviceDeskId: Int
) : AtlassianResource<PostServiceDeskRequestResponse> {
  private val path = "/rest/servicedeskapi/request"
  private val httpMethod = HttpMethod.Post

  override fun buildRequest(requestBuilder: HttpRequestBuilder) {
    val body =
        PostServiceDeskRequestBody(
            description = description,
            requestTypeId = requestTypeId,
            summary = summary,
            raiseOnBehalfOf = reporter,
            serviceDeskId = serviceDeskId,
        )
    with(requestBuilder) {
      method = httpMethod
      url { path(path) }
      setBody(body)
    }
  }

  override suspend fun parseResponse(response: HttpResponse): PostServiceDeskRequestResponse {
    return response.body()
  }

  /** A builder class to inject configs or build information into the resource. */
  @Named
  class Builder(private val config: TerrawareServerConfig) {
    private var description: String? = null
    private var summary: String? = null
    private var supportRequestType: SupportRequestType? = null
    private var reporter: String? = null

    fun description(description: String) = apply { this.description = description }

    fun summary(summary: String) = apply { this.summary = summary }

    fun reporter(reporter: String) = apply { this.reporter = reporter }

    fun supportRequestType(supportRequestType: SupportRequestType) = apply {
      this.supportRequestType = supportRequestType
    }

    fun build(): CreateServiceDeskRequest {
      val requestTypeId =
          when (supportRequestType) {
            SupportRequestType.BUG_REPORT -> config.atlassian.bugReportTypeId!!
            SupportRequestType.FEATURE_REQUEST -> config.atlassian.featureRequestTypeId!!
            null -> throw RuntimeException("Support request type is not set")
          }

      if (summary == null || description == null) {
        throw RuntimeException("Summary and description are not set")
      } else {
        return CreateServiceDeskRequest(
            description = description!!,
            summary = summary!!,
            reporter = reporter,
            requestTypeId = requestTypeId,
            serviceDeskId = config.atlassian.serviceDeskId!!,
        )
      }
    }
  }
}

data class PostServiceDeskRequestBody(
    val raiseOnBehalfOf: String?,
    val requestFieldValues: ServiceRequestFields,
    val requestTypeId: Int,
    val serviceDeskId: Int,
) {
  constructor(
      description: String,
      raiseOnBehalfOf: String? = null,
      requestTypeId: Int,
      serviceDeskId: Int,
      summary: String,
  ) : this(
      raiseOnBehalfOf = raiseOnBehalfOf,
      requestFieldValues = ServiceRequestFields(summary, description),
      requestTypeId = requestTypeId,
      serviceDeskId = serviceDeskId,
  )
}

data class ServiceRequestFields(val summary: String, val description: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PostServiceDeskRequestResponse(val issueId: String, val issueKey: String)
