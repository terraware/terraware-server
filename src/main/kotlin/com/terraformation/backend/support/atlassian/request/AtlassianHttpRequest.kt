package com.terraformation.backend.support.atlassian.request

import com.terraformation.backend.support.atlassian.AtlassianHttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse

/**
 * Every Atlassian API call will implement this interface. The intended workflow is that every time
 * an API call is made, an instance of this interface will be created. The [AtlassianHttpClient]
 * will invoke the [buildRequest] method so the caller will not be able to erroneously or
 * maliciously set the request body.
 */
interface AtlassianHttpRequest<T> {
  fun buildRequest(requestBuilder: HttpRequestBuilder)

  suspend fun parseResponse(response: HttpResponse): T
}
