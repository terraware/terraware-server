package com.terraformation.backend.support

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.email.EmailService
import com.terraformation.backend.email.model.SupportRequestSubmitted
import com.terraformation.backend.support.atlassian.AtlassianHttpClient
import com.terraformation.backend.support.atlassian.model.ServiceRequestTypeModel
import jakarta.inject.Named

@Named
class SupportService(
    private val atlassianHttpClient: AtlassianHttpClient,
    private val config: TerrawareServerConfig,
    private val emailService: EmailService,
    private val userStore: UserStore,
) {
  fun submitServiceRequest(description: String, summary: String, requestTypeId: Int) {
    val user = userStore.fetchOneById(currentUser().userId) as IndividualUser
    val response =
        atlassianHttpClient.createServiceDeskRequest(
            description, summary, requestTypeId, user.email)
    val requestType =
        atlassianHttpClient.requestTypes[requestTypeId]
            ?: throw IllegalArgumentException("Request ID type not recognized")

    emailService.sendUserNotification(
        user,
        SupportRequestSubmitted(config, requestType.name, response.issueKey, summary, description),
        false,
    )
  }

  fun listServiceRequestTypes(): List<ServiceRequestTypeModel> {
    return atlassianHttpClient.requestTypes.values.toList()
  }
}
