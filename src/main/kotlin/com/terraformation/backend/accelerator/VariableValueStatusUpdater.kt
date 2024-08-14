package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.event.VariableValueUpdatedEvent
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.documentproducer.db.VariableWorkflowStore
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class VariableValueStatusUpdater(
    private val systemUser: SystemUser,
    private val variableWorkflowStore: VariableWorkflowStore,
) {
  /** Update variable status to "in review" when value is updated */
  @EventListener
  fun on(event: VariableValueUpdatedEvent) {
    val createdBy = currentUser().userId

    systemUser.run {
      variableWorkflowStore.update(
          event.projectId, event.variableId, VariableWorkflowStatus.InReview, null, null, createdBy)
    }
  }
}
