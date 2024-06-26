package com.terraformation.backend.support.atlassian.request

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.terraformation.backend.support.atlassian.model.JiraServiceRequestTypeModel
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.path

class ListServiceRequestTypesHttpRequest(serviceDeskId: Int) :
    AtlassianHttpRequest<ListServiceRequestTypesResponse> {
  private val path = "/rest/servicedeskapi/servicedesk/$serviceDeskId/requesttype/"
  private val httpMethod = HttpMethod.Get

  override fun buildRequest(requestBuilder: HttpRequestBuilder) {
    with(requestBuilder) {
      method = httpMethod
      url { path(path) }
    }
  }

  override suspend fun parseResponse(response: HttpResponse): ListServiceRequestTypesResponse {
    return response.body()
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ListServiceRequestTypesResponse(val values: List<JiraServiceRequestTypeModel>)
