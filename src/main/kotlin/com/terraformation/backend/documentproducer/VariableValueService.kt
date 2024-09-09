package com.terraformation.backend.documentproducer

import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.model.AppendValueOperation
import com.terraformation.backend.documentproducer.model.DeleteValueOperation
import com.terraformation.backend.documentproducer.model.ReplaceValuesOperation
import com.terraformation.backend.documentproducer.model.UpdateValueOperation
import com.terraformation.backend.documentproducer.model.ValueOperation
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.documentproducer.model.VariableValue
import jakarta.inject.Named

@Named
class VariableValueService(
    private val variableStore: VariableStore,
) {
  /**
   * Checks the values that would be created by a list of operations to make sure they are all valid
   * for their respective variables. Throws exceptions on validation failure; see
   * [Variable.validate] for details.
   */
  fun validate(operations: List<ValueOperation>) {
    if (operations.isEmpty()) {
      return
    }

    operations.forEach { operation ->
      when (operation) {
        is AppendValueOperation -> validate(operation.value)
        is DeleteValueOperation -> Unit
        is ReplaceValuesOperation -> operation.values.forEach { newValue -> validate(newValue) }
        is UpdateValueOperation -> validate(operation.value)
      }
    }
  }

  /**
   * Checks that a single value is valid for its variable. Throws exceptions on validation failure;
   * see [Variable.validate] for details.
   */
  fun validate(newValue: VariableValue<*, *>) {
    val variable = variableStore.fetchOneVariable(newValue.variableId)

    variable.validate(newValue, variableStore::fetchOneVariable)
  }
}
