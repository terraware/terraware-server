package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.model.ApplicationSubmissionResult
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import jakarta.inject.Named

@Named
class ApplicationService(
    private val applicationStore: ApplicationStore,
    private val preScreenVariableValuesFetcher: PreScreenVariableValuesFetcher,
) {
  /**
   * Submits an application. If the submission is for pre-screening, looks up the values of the
   * variables that affect the eligibility checks.
   *
   * The variable fetching happens here rather than directly in [ApplicationStore] to avoid adding a
   * peer dependency between store classes, since [PreScreenVariableValuesFetcher] depends on the
   * variable stores.
   */
  fun submit(applicationId: ApplicationId): ApplicationSubmissionResult {
    requirePermissions { updateApplicationSubmissionStatus(applicationId) }

    val existing = applicationStore.fetchOneById(applicationId)

    val variableValues =
        if (existing.status == ApplicationStatus.NotSubmitted) {
          preScreenVariableValuesFetcher.fetchValues(existing.projectId)
        } else {
          null
        }

    return applicationStore.submit(applicationId, variableValues)
  }
}
