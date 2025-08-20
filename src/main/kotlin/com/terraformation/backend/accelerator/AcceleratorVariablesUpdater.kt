package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.accelerator.event.VariableValueUpdatedEvent
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.model.StableIds
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

@Named
class AcceleratorVariablesUpdater(
    private val systemUser: SystemUser,
    private val applicationStore: ApplicationStore,
    private val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService,
    private val dslContext: DSLContext,
    private val variableStore: VariableStore,
) {

  @EventListener
  fun on(event: VariableValueUpdatedEvent) {
    systemUser.run {
      val variable = variableStore.fetchOneVariable(event.variableId)
      val values = acceleratorProjectVariableValuesService.fetchValues(event.projectId)

      if (variable.stableId == StableIds.country) {
        // Update application country when project country variable is updated
        val application = applicationStore.fetchByProjectId(event.projectId).singleOrNull()
        if (application != null) {
          values.countryCode?.let { applicationStore.updateCountryCode(application.id, it) }
        }
      } else if (variable.stableId == StableIds.dealName) {
        // Update accelerator details deal name when project deal name variable is updated
        dslContext
            .insertInto(PROJECT_ACCELERATOR_DETAILS)
            .set(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID, event.projectId)
            .set(PROJECT_ACCELERATOR_DETAILS.DEAL_NAME, values.dealName)
            .onConflict()
            .doUpdate()
            .set(PROJECT_ACCELERATOR_DETAILS.DEAL_NAME, values.dealName)
            .execute()
      }
    }
  }

  @EventListener
  fun on(event: ApplicationInternalNameUpdatedEvent) {
    systemUser.run {
      val application = applicationStore.fetchOneById(event.applicationId)
      val values = acceleratorProjectVariableValuesService.fetchValues(application.projectId)

      acceleratorProjectVariableValuesService.writeValues(
          application.projectId,
          values.copy(dealName = application.internalName),
      )
    }
  }
}
