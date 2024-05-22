package com.terraformation.backend.support.atlassian.request

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.terraformation.backend.support.atlassian.model.TemporaryAttachmentModel
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.path

class CreateAttachmentsHttpRequest(issueId: String, attachmentIds: List<String>, comment: String?) :
    AtlassianHttpRequest<CreateAttachmentsResponse> {
  private val path = "/rest/servicedeskapi/request/$issueId/attachment"
  private val httpMethod = HttpMethod.Post
  private val requestBody =
      CreateAttachmentsRequestBody(
          temporaryAttachmentIds = attachmentIds,
          additionalComment = comment?.let { CreateAttachmentsAdditionalComment(comment) })

  override fun buildRequest(requestBuilder: HttpRequestBuilder) {
    with(requestBuilder) {
      method = httpMethod
      url { path(path) }
      setBody(requestBody)
    }
  }

  override suspend fun parseResponse(response: HttpResponse): CreateAttachmentsResponse {
    return response.body()
  }
}

data class CreateAttachmentsAdditionalComment(
    val body: String,
)

data class CreateAttachmentsRequestBody(
    val temporaryAttachmentIds: List<String>,
    val public: Boolean = true,
    val additionalComment: CreateAttachmentsAdditionalComment?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CreateAttachmentsResponse(val temporaryAttachments: List<TemporaryAttachmentModel>)
