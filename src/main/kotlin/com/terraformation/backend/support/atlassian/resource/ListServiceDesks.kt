package com.terraformation.backend.support.atlassian.resource

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.terraformation.backend.support.atlassian.model.ServiceDeskModel
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.path

class ListServiceDesks : AtlassianResource<ListServiceDesksResponse> {
  private val path = "/rest/servicedeskapi/servicedesk/"
  private val httpMethod = HttpMethod.Get

  override fun buildRequest(requestBuilder: HttpRequestBuilder) {
    with(requestBuilder) {
      method = httpMethod
      url { path(path) }
    }
  }

  override suspend fun parseResponse(response: HttpResponse): ListServiceDesksResponse {
    return response.body()
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ListServiceDesksResponse(val values: List<ServiceDeskModel>)
