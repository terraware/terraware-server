package com.terraformation.backend.support.atlassian.resource

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.support.atlassian.SupportRequestType
import javax.inject.Named

/** A factory class to inject configs or build information into the resource. */
@Named
class AtlassianResourceFactory(private val config: TerrawareServerConfig) {
  fun createServiceDeskRequest(
      description: String,
      summary: String,
      reporter: String,
      requestType: SupportRequestType,
  ): CreateServiceDeskRequest =
      CreateServiceDeskRequest(
          description = description,
          summary = summary,
          reporter = reporter,
          requestTypeId = getSupportRequestTypeId(requestType),
          serviceDeskId = config.atlassian.serviceDeskId!!,
      )

  fun deleteIssue(issueId: String): DeleteIssue = DeleteIssue(issueId)

  private fun getSupportRequestTypeId(requestType: SupportRequestType) =
      when (requestType) {
        SupportRequestType.BUG_REPORT -> config.atlassian.bugReportTypeId!!
        SupportRequestType.FEATURE_REQUEST -> config.atlassian.featureRequestTypeId!!
      }
}
