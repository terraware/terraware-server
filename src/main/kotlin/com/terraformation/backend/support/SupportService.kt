package com.terraformation.backend.support

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.TerraformationContactMissingEvent
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.email.EmailService
import com.terraformation.backend.email.model.SupportRequestSubmitted
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.use
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.support.atlassian.AtlassianHttpClient
import com.terraformation.backend.support.atlassian.MAX_JIRA_DESCRIPTION_LENGTH
import com.terraformation.backend.support.atlassian.MAX_JIRA_SUMMARY_LENGTH
import com.terraformation.backend.support.atlassian.model.SupportRequestType
import com.terraformation.backend.support.atlassian.model.TemporaryAttachmentModel
import jakarta.inject.Named
import jakarta.ws.rs.NotSupportedException
import java.util.Locale
import org.apache.tika.Tika
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

@Named
class SupportService(
    private val atlassianHttpClient: AtlassianHttpClient,
    private val config: TerrawareServerConfig,
    private val emailService: EmailService,
    private val messages: Messages,
    private val systemUser: SystemUser,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(event: TerraformationContactMissingEvent) {
    if (!config.atlassian.enabled) {
      log.info("Atlassian integration disabled; not filing missing-contact support request")
      return
    }

    Locale.ENGLISH.use {
      val missingContact = messages.missingContact(event.organizationName, event.organizationId)
      submitServiceRequest(
          requestType = SupportRequestType.MissingContact,
          summary = missingContact.title.truncateForJira(MAX_JIRA_SUMMARY_LENGTH),
          description =
              "${missingContact.body}\n\n${event.subject}\n\n${event.body}"
                  .truncateForJira(MAX_JIRA_DESCRIPTION_LENGTH),
          skipReceiptEmail = true,
          userId = systemUser.userId,
      )
    }
  }

  private fun String.truncateForJira(maxLength: Int): String =
      if (length <= maxLength) this
      else take(maxLength - 3).trimEnd { it.isHighSurrogate() } + "..."

  fun submitServiceRequest(
      requestType: SupportRequestType,
      summary: String,
      description: String,
      attachmentIds: List<String> = emptyList(),
      attachmentComment: String? = null,
      skipReceiptEmail: Boolean = false,
      userId: UserId = currentUser().userId,
  ): String {
    val user = userStore.fetchOneById(userId)
    if (user !is IndividualUser && user !is SystemUser) {
      throw AccessDeniedException("Only individual and system users may submit support requests")
    }
    val response =
        atlassianHttpClient.createServiceDeskRequest(description, summary, requestType, user.email)

    if (attachmentIds.isNotEmpty()) {
      atlassianHttpClient.createAttachments(response.issueId, attachmentIds, attachmentComment)
    }

    if (!skipReceiptEmail) {
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
    }

    return response.issueKey
  }

  fun listSupportRequestTypes(): List<SupportRequestType> {
    return atlassianHttpClient.requestTypeIds.keys.filter { !it.isInternal }
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
