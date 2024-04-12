package com.terraformation.backend.support.atlassian.resource

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.path

/**
 * The POST request to create a service ticket.
 *
 * @param requestTypeId the ID of the Jira issue type
 * @param summary the title of the Jira issue
 * @param description the details of the Jira issue
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
