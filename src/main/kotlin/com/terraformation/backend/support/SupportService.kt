package com.terraformation.backend.support

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.email.EmailService
import com.terraformation.backend.email.model.SupportRequestSubmitted
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.support.atlassian.AtlassianHttpClient
import com.terraformation.backend.support.atlassian.model.SupportRequestType
import com.terraformation.backend.support.atlassian.model.TemporaryAttachmentModel
import jakarta.inject.Named
import jakarta.ws.rs.NotSupportedException
import org.apache.tika.Tika
import org.springframework.http.MediaType

@Named
class SupportService(
    private val atlassianHttpClient: AtlassianHttpClient,
    private val config: TerrawareServerConfig,
    private val emailService: EmailService,
    private val messages: Messages,
    private val userStore: UserStore,
) {
  fun submitServiceRequest(
      requestType: SupportRequestType,
      summary: String,
      description: String,
      attachmentIds: List<String> = emptyList(),
      attachmentComment: String? = null,
  ): String {
    val user = userStore.fetchOneById(currentUser().userId) as IndividualUser
    val response =
        atlassianHttpClient.createServiceDeskRequest(description, summary, requestType, user.email)

    if (attachmentIds.isNotEmpty()) {
      atlassianHttpClient.createAttachments(response.issueId, attachmentIds, attachmentComment)
    }

    emailService.sendUserNotification(
        user,
        SupportRequestSubmitted(
            config,
            messages.supportRequestTypeName(requestType),
            response.issueKey,
            summary,
            description,
        ),
        false,
    )

    return response.issueKey
  }

  fun listSupportRequestTypes(): List<SupportRequestType> {
    return atlassianHttpClient.requestTypeIds.keys.toList()
  }

  fun attachTemporaryFile(
      filename: String,
      sizedInputStream: SizedInputStream,
  ): List<TemporaryAttachmentModel> {

    sizedInputStream.contentType?.let {
      if (!isContentTypeSupported(it)) {
        throw NotSupportedException(
            "$it is not a supported content type. Must be one of $SUPPORTED_CONTENT_TYPES_STRING"
        )
      }
    }

    if (sizedInputStream.markSupported()) {
      // Only perform a file type detection if the Input Stream supports resetting
      sizedInputStream.mark(sizedInputStream.size.toInt())
      val tikaContentType = MediaType.parseMediaType(Tika().detect(sizedInputStream))
      if (!isContentTypeSupported(tikaContentType)) {
        throw NotSupportedException(
            "File detected to be $tikaContentType, which is not supported. Must be one of $SUPPORTED_CONTENT_TYPES_STRING"
        )
      }
      sizedInputStream.reset()
    }

    return atlassianHttpClient.attachTemporaryFile(filename, sizedInputStream).temporaryAttachments
  }
}
