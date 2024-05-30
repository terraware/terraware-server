package com.terraformation.backend.support.atlassian.request

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.terraformation.backend.support.atlassian.model.JiraServiceRequestFieldsModel
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.path

/**
 * The POST request to create a service ticket.
 *
 * @param requestFieldValues the title of the Jira issue
 * @param requestTypeId the ID of the Jira issue type
 * @param reporter the email address of the reporter
 * @param serviceDeskId the ID for the service desk
 */
class CreateServiceRequestHttpRequest(
    reporter: String? = null,
    requestFieldValues: JiraServiceRequestFieldsModel,
    requestTypeId: Int,
    serviceDeskId: Int,
) : AtlassianHttpRequest<PostServiceDeskRequestResponse> {
  private val path = "/rest/servicedeskapi/request"
  private val httpMethod = HttpMethod.Post
  private val requestBody =
      PostServiceDeskRequestBody(
          requestFieldValues = requestFieldValues,
          requestTypeId = requestTypeId,
          raiseOnBehalfOf = reporter,
          serviceDeskId = serviceDeskId,
      )

  override fun buildRequest(requestBuilder: HttpRequestBuilder) {
    with(requestBuilder) {
      method = httpMethod
      url { path(path) }
      setBody(requestBody)
    }
  }

  override suspend fun parseResponse(response: HttpResponse): PostServiceDeskRequestResponse {
    return response.body()
  }
}

data class PostServiceDeskRequestBody(
    val raiseOnBehalfOf: String?,
    val requestFieldValues: JiraServiceRequestFieldsModel,
    val requestTypeId: Int,
    val serviceDeskId: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PostServiceDeskRequestResponse(val issueId: String, val issueKey: String)
