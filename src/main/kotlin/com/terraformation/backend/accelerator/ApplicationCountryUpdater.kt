package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.ApplicationVariableValuesFetcher.Companion.STABLE_ID_COUNTRY
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.event.VariableValueUpdatedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.documentproducer.db.VariableStore
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class ApplicationCountryUpdater(
    private val systemUser: SystemUser,
    private val applicationStore: ApplicationStore,
    private val variableStore: VariableStore,
    private val applicationVariableValuesFetcher: ApplicationVariableValuesFetcher,
) {

  /** Update application country when project country variable is updated */
  @EventListener
  fun on(event: VariableValueUpdatedEvent) {
    systemUser.run {
      val variable = variableStore.fetchOneVariable(event.variableId)
      val application = applicationStore.fetchByProjectId(event.projectId).firstOrNull()

      // If the project has an application, and the country variable is updated
      if (variable.stableId == STABLE_ID_COUNTRY && application != null) {
        val variableValues = applicationVariableValuesFetcher.fetchValues(event.projectId)
        variableValues.countryCode?.let {
          applicationStore.updateCountryCode(application.id, it)
          applicationStore.updateInternalName(application.id)
        }
      }
    }
  }
}
