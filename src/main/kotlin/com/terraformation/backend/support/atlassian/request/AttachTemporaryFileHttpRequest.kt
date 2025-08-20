package com.terraformation.backend.support.atlassian.request

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.terraformation.backend.support.atlassian.model.TemporaryAttachmentModel
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.path
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.InputStream

class AttachTemporaryFilesHttpRequest(
    serviceDeskId: Int,
    inputStream: InputStream,
    private val filename: String,
    private val contentType: String?,
    private val fileSize: Long?,
) : AtlassianHttpRequest<AttachTemporaryFileResponse> {
  private val path = "/rest/servicedeskapi/servicedesk/$serviceDeskId/attachTemporaryFile"
  private val httpMethod = HttpMethod.Post
  private val byteReadChannel = inputStream.toByteReadChannel()

  override fun buildRequest(requestBuilder: HttpRequestBuilder) {
    val requestBody =
        MultiPartFormDataContent(
            formData {
              append(
                  "file",
                  ChannelProvider(fileSize) { byteReadChannel },
                  Headers.build {
                    contentType?.let { append(HttpHeaders.ContentType, it) }
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                  },
              )
            }
        )

    with(requestBuilder) {
      method = httpMethod
      url { path(path) }
      headers {
        append("X-ExperimentalApi", "opt-in")
        append("X-Atlassian-Token", "no-check")
      }
      setBody(requestBody)
    }
  }

  override suspend fun parseResponse(response: HttpResponse): AttachTemporaryFileResponse {
    return response.body()
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AttachTemporaryFileResponse(val temporaryAttachments: List<TemporaryAttachmentModel>)
