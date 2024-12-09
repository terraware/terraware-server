package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.accelerator.event.VariableValueUpdatedEvent
import com.terraformation.backend.accelerator.variables.ApplicationVariableValuesService
import com.terraformation.backend.accelerator.variables.StableId
import com.terraformation.backend.accelerator.variables.StableIds
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.documentproducer.db.VariableStore
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class ApplicationVariablesUpdater(
    private val systemUser: SystemUser,
    private val applicationStore: ApplicationStore,
    private val variableStore: VariableStore,
    private val applicationVariableValuesService: ApplicationVariableValuesService,
) {

  /** Update application country when project country variable is updated */
  @EventListener
  fun on(event: VariableValueUpdatedEvent) {
    systemUser.run {
      val variable = variableStore.fetchOneVariable(event.variableId)

      if (StableId(variable.stableId) == StableIds.country) {
        val application = applicationStore.fetchByProjectId(event.projectId).singleOrNull()
        if (application != null) {
          val variableValues = applicationVariableValuesService.fetchValues(event.projectId)
          variableValues.countryCode?.let { applicationStore.updateCountryCode(application.id, it) }
        }
      }
    }
  }

  @EventListener
  fun on(event: ApplicationInternalNameUpdatedEvent) {
    systemUser.run {
      val application = applicationStore.fetchOneById(event.applicationId)
      applicationVariableValuesService.updateDealName(
          application.projectId,
          application.internalName,
      )
    }
  }
}
