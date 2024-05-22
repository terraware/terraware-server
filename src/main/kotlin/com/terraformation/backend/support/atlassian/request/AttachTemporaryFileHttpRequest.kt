package com.terraformation.backend.support.atlassian.request

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.terraformation.backend.api.getFilename
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
import io.ktor.util.cio.toByteReadChannel
import org.springframework.web.multipart.MultipartFile

class AttachTemporaryFilesHttpRequest(serviceDeskId: Int, file: MultipartFile) :
    AtlassianHttpRequest<AttachTemporaryFileResponse> {
  private val path = "/servicedeskapi/servicedesk/$serviceDeskId/attachTemporaryFile"
  private val httpMethod = HttpMethod.Post
  private val byteReadChannel = file.inputStream.toByteReadChannel()
  private val filename = file.getFilename()
  private val contentType = file.contentType

  override fun buildRequest(requestBuilder: HttpRequestBuilder) {
    val requestBody =
        MultiPartFormDataContent(
            formData {
              append(
                  "file",
                  ChannelProvider { byteReadChannel },
                  Headers.build {
                    contentType?.let { append(HttpHeaders.ContentType, it) }
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                  })
            })

    with(requestBuilder) {
      method = httpMethod
      url { path(path) }
      headers { append("X-ExperimentalApi", "opt-in") }
      setBody(requestBody)
    }
  }

  override suspend fun parseResponse(response: HttpResponse): AttachTemporaryFileResponse {
    return response.body()
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AttachTemporaryFileResponse(val temporaryAttachments: List<TemporaryAttachmentModel>)
