package com.terraformation.backend.support.api

import com.terraformation.backend.api.ApiResponse413
import com.terraformation.backend.api.ApiResponse415
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.SupportEndpoint
import com.terraformation.backend.api.getFilename
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.support.SUPPORTED_CONTENT_TYPES_STRING
import com.terraformation.backend.support.SupportService
import com.terraformation.backend.support.atlassian.model.SupportRequestType
import com.terraformation.backend.support.atlassian.model.TemporaryAttachmentModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.NotSupportedException
import java.io.BufferedInputStream
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RequestMapping("/api/v1/support")
@RestController
@SupportEndpoint
class SupportController(private val service: SupportService) {
  private val log = perClassLogger()

  @ApiResponse(responseCode = "200")
  @GetMapping
  @Operation(summary = "Lists support request types.")
  fun listRequestTypes(): ListSupportRequestTypesResponsePayload {
    return ListSupportRequestTypesResponsePayload(service.listSupportRequestTypes())
  }

  @ApiResponse(responseCode = "200")
  @PostMapping
  @Operation(summary = "Submit support request types.")
  fun submitRequest(
      @RequestBody payload: SubmitSupportRequestPayload
  ): SubmitSupportRequestResponsePayload {
    val issueKey =
        service.submitServiceRequest(
            requestType = payload.requestType,
            summary = payload.summary,
            description = payload.description,
            attachmentIds = payload.attachmentIds ?: emptyList(),
            attachmentComment = payload.attachmentComment,
        )
    return SubmitSupportRequestResponsePayload(issueKey)
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse413
  @ApiResponse415
  @Operation(
      summary = "Upload a temporary attachment.",
      description =
          "Uploads an attachment, which can be assigned to a support request during submission.",
  )
  @PostMapping("/attachment", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content =
          [
              Content(
                  encoding = [Encoding(name = "file", contentType = SUPPORTED_CONTENT_TYPES_STRING)]
              )
          ]
  )
  fun uploadAttachment(
      @RequestPart("file", required = true) file: MultipartFile,
  ): UploadAttachmentResponsePayload {

    val sizedInputStream =
        SizedInputStream(
            BufferedInputStream(file.inputStream), // BufferedInputStream supports mark/reset
            file.size,
            file.contentType?.let { MediaType.parseMediaType(it) },
        )
    val temporaryAttachments =
        try {
          service.attachTemporaryFile(file.getFilename(), sizedInputStream)
        } catch (e: NotSupportedException) {
          throw e
        } catch (e: Exception) {
          log.error("Unable to upload ${file.getFilename()}", e)
          throw InternalServerErrorException("Unable to upload the attachment.")
        }

    return UploadAttachmentResponsePayload(temporaryAttachments.map { TemporaryAttachment(it) })
  }
}

data class ListSupportRequestTypesResponsePayload(val types: List<SupportRequestType>) :
    SuccessResponsePayload

data class SubmitSupportRequestPayload(
    val requestType: SupportRequestType,
    val description: String,
    val summary: String,
    val attachmentIds: List<String>?,
    val attachmentComment: String?,
)

data class SubmitSupportRequestResponsePayload(val issueKey: String) : SuccessResponsePayload

data class TemporaryAttachment(
    val temporaryAttachmentId: String,
    val filename: String,
) {
  constructor(
      model: TemporaryAttachmentModel
  ) : this(temporaryAttachmentId = model.temporaryAttachmentId, filename = model.fileName)
}

data class UploadAttachmentResponsePayload(val attachments: List<TemporaryAttachment>) :
    SuccessResponsePayload
