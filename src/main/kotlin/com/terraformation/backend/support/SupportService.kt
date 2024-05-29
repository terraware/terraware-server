package com.terraformation.backend.support

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.email.EmailService
import com.terraformation.backend.email.model.SupportRequestSubmitted
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.support.atlassian.AtlassianHttpClient
import com.terraformation.backend.support.atlassian.model.ServiceRequestTypeModel
import com.terraformation.backend.support.atlassian.model.TemporaryAttachmentModel
import jakarta.inject.Named

@Named
class SupportService(
    private val atlassianHttpClient: AtlassianHttpClient,
    private val config: TerrawareServerConfig,
    private val emailService: EmailService,
    private val userStore: UserStore,
) {
  fun submitServiceRequest(
      requestTypeId: Int,
      summary: String,
      description: String,
      attachmentIds: List<String> = emptyList(),
      attachmentComment: String? = null,
  ): String {
    val user = userStore.fetchOneById(currentUser().userId) as IndividualUser
    val requestType =
        atlassianHttpClient.requestTypes[requestTypeId]
            ?: throw IllegalArgumentException("Request ID type not recognized")
    val response =
        atlassianHttpClient.createServiceDeskRequest(
            description, summary, requestTypeId, user.email)

    if (attachmentIds.isNotEmpty()) {
      atlassianHttpClient.createAttachments(response.issueId, attachmentIds, attachmentComment)
    }

    emailService.sendUserNotification(
        user,
        SupportRequestSubmitted(config, requestType.name, response.issueKey, summary, description),
        false,
    )

    return response.issueKey
  }

  fun listServiceRequestTypes(): List<ServiceRequestTypeModel> {
    return atlassianHttpClient.requestTypes.values.toList()
  }

  fun attachTemporaryFile(
      sizedInputStream: SizedInputStream,
      filename: String
  ): List<TemporaryAttachmentModel> {
    return atlassianHttpClient.attachTemporaryFile(sizedInputStream, filename).temporaryAttachments
  }
}
