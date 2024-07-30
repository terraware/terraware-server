package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.event.VariableValueUpdatedEvent
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.documentproducer.db.VariableWorkflowStore
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class VariableValueStatusNotifier(
    private val variableWorkflowStore: VariableWorkflowStore,
) {

  /** Update variable status to "in review" when value is updated */
  @EventListener
  fun on(event: VariableValueUpdatedEvent) {
    variableWorkflowStore.update(
        event.projectId, event.variableId, VariableWorkflowStatus.InReview, null, null)
  }
}
