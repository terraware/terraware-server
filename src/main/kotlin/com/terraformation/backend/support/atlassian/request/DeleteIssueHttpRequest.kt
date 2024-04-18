package com.terraformation.backend.support.atlassian.request

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.path

/** DELETE request to delete an issue. */
class DeleteIssueHttpRequest(issueId: String) : AtlassianHttpRequest<Unit> {
  private val path = "/rest/api/3/issue/$issueId"
  private val httpMethod = HttpMethod.Delete

  override fun buildRequest(requestBuilder: HttpRequestBuilder) {
    with(requestBuilder) {
      method = httpMethod
      url { path(path) }
    }
  }

  override suspend fun parseResponse(response: HttpResponse) = Unit
}
