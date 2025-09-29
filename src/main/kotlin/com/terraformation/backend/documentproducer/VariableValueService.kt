package com.terraformation.backend.documentproducer

import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_VARIABLES
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.db.VariableWorkflowStore
import com.terraformation.backend.documentproducer.model.AppendValueOperation
import com.terraformation.backend.documentproducer.model.DeleteValueOperation
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.ReplaceValuesOperation
import com.terraformation.backend.documentproducer.model.UpdateValueOperation
import com.terraformation.backend.documentproducer.model.ValueOperation
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.documentproducer.model.VariableValue
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class VariableValueService(
    private val dslContext: DSLContext,
    private val systemUser: SystemUser,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
    private val variableWorkflowStore: VariableWorkflowStore,
) {
  /**
   * Service method to validate, write values, and update workflows.
   *
   * @param triggerWorkflows whether to trigger notifications and status changes workflows. Requires
   *   additional privilege to not trigger workflows and should be used by admin overwrites of
   *   values.
   */
  fun updateValues(operations: List<ValueOperation>, triggerWorkflows: Boolean = true) {
    validate(operations)
    val values = variableValueStore.updateValues(operations, triggerWorkflows)
    if (triggerWorkflows) {
      systemUser.run { updateWorkflowHistory(values) }
    }
  }

  /**
   * Creates new workflow history elements for each updated value.
   *
   * @param values list of updated values
   */
  private fun updateWorkflowHistory(values: List<ExistingValue>) {
    val projectId = values.firstOrNull()?.projectId ?: return

    val variableIdsInDeliverables: Set<VariableId> =
        with(DELIVERABLE_VARIABLES) {
          dslContext
              .select(VARIABLE_ID)
              .from(DELIVERABLE_VARIABLES)
              .where(VARIABLE_ID.`in`(values.map { it.variableId }.toSet()))
              .fetchSet(VARIABLE_ID.asNonNullable())
        }

    val valuesByVariables =
        values.filter { it.variableId in variableIdsInDeliverables }.groupBy { it.variableId }

    valuesByVariables.keys.forEach { variableId ->
      variableWorkflowStore.update(projectId, variableId) {
        it.copy(
            status = VariableWorkflowStatus.InReview,
            feedback = null,
        )
      }
    }
  }

  /**
   * Checks the values that would be created by a list of operations to make sure they are all valid
   * for their respective variables. Throws exceptions on validation failure; see
   * [Variable.validate] for details.
   */
  private fun validate(operations: List<ValueOperation>) {
    if (operations.isEmpty()) {
      return
    }

    operations.forEach { operation ->
      when (operation) {
        is AppendValueOperation -> validate(operation.value)
        is DeleteValueOperation -> validateDelete(operation.valueId)
        is ReplaceValuesOperation -> operation.values.forEach { newValue -> validate(newValue) }
        is UpdateValueOperation -> validate(operation.value)
      }
    }
  }

  /**
   * Checks that a single value is valid for its variable. Throws exceptions on validation failure;
   * see [Variable.validate] for details.
   */
  private fun validate(newValue: VariableValue<*, *>) {
    val variable = variableStore.fetchOneVariable(newValue.variableId)

    if (variable.internalOnly) {
      requirePermissions { updateInternalOnlyVariables() }
    }

    variable.validate(newValue, variableStore::fetchOneVariable)
  }

  private fun validateDelete(valueId: VariableValueId) {
    val variable =
        variableStore.fetchOneVariable(variableValueStore.fetchOneById(valueId).variableId)

    if (variable.internalOnly) {
      requirePermissions { updateInternalOnlyVariables() }
    }
  }
}
