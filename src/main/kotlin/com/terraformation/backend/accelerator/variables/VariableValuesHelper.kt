package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.documentproducer.model.AppendValueOperation
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.DeleteValueOperation
import com.terraformation.backend.documentproducer.model.ExistingLinkValue
import com.terraformation.backend.documentproducer.model.ExistingNumberValue
import com.terraformation.backend.documentproducer.model.ExistingSelectValue
import com.terraformation.backend.documentproducer.model.ExistingTextValue
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.LinkValueDetails
import com.terraformation.backend.documentproducer.model.LinkVariable
import com.terraformation.backend.documentproducer.model.NewLinkValue
import com.terraformation.backend.documentproducer.model.NewNumberValue
import com.terraformation.backend.documentproducer.model.NewSelectValue
import com.terraformation.backend.documentproducer.model.NewTextValue
import com.terraformation.backend.documentproducer.model.NumberVariable
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.documentproducer.model.TextVariable
import com.terraformation.backend.documentproducer.model.UpdateValueOperation
import com.terraformation.backend.documentproducer.model.ValueOperation
import com.terraformation.backend.documentproducer.model.Variable
import java.math.BigDecimal
import java.net.URI

fun getValueId(
    valuesByStableId: Map<StableId, ExistingValue>,
    stableId: StableId,
): VariableValueId? {
  return (valuesByStableId[stableId])?.id
}

fun getNumberValue(
    valuesByStableId: Map<StableId, ExistingValue>,
    stableId: StableId,
): BigDecimal? {
  return (valuesByStableId[stableId] as? ExistingNumberValue)?.value
}

fun getTextValue(valuesByStableId: Map<StableId, ExistingValue>, stableId: StableId): String? {
  return (valuesByStableId[stableId] as? ExistingTextValue)?.value
}

fun getLinkValue(valuesByStableId: Map<StableId, ExistingValue>, stableId: StableId): URI? {
  return (valuesByStableId[stableId] as? ExistingLinkValue)?.value?.url
}

fun getSingleSelectValue(
    variables: Map<VariableId, Variable>,
    valuesByStableId: Map<StableId, ExistingValue>,
    stableId: StableId,
): String? {
  val selectValue = valuesByStableId[stableId] as? ExistingSelectValue ?: return null
  val variable = variables[selectValue.variableId] as? SelectVariable ?: return null
  val selectOptionId = selectValue.value.firstOrNull() ?: return null

  return variable.options.firstOrNull { it.id == selectOptionId }?.name
}

fun getMultiSelectValue(
    variables: Map<VariableId, Variable>,
    valuesByStableId: Map<StableId, ExistingValue>,
    stableId: StableId,
): Set<String>? {
  val selectValue = valuesByStableId[stableId] as? ExistingSelectValue ?: return null
  val variable = variables[selectValue.variableId] as? SelectVariable ?: return null
  val selectOptionIds = selectValue.value

  return selectOptionIds
      .mapNotNull { optionId -> variable.options.firstOrNull { it.id == optionId }?.name }
      .toSet()
}

fun updateNumberValueOperation(
    projectId: ProjectId,
    variable: NumberVariable,
    existingValue: ExistingNumberValue?,
    newValue: BigDecimal?,
): ValueOperation? {
  if (newValue != null) {
    return AppendValueOperation(
        NewNumberValue(
            BaseVariableValueProperties(null, projectId, 0, variable.id, null, null),
            newValue,
        )
    )
  } else if (existingValue != null) {
    return DeleteValueOperation(projectId, existingValue.id)
  } else {
    // Both existing and new are null. No-op
    return null
  }
}

fun updateTextValueOperation(
    projectId: ProjectId,
    variable: TextVariable,
    existingValue: ExistingTextValue?,
    newValue: String?,
): ValueOperation? {
  if (newValue != null) {
    return AppendValueOperation(
        NewTextValue(
            BaseVariableValueProperties(null, projectId, 0, variable.id, null, null),
            newValue,
        )
    )
  } else if (existingValue != null) {
    return DeleteValueOperation(projectId, existingValue.id)
  } else {
    // Both existing and new are null. No-op
    return null
  }
}

fun updateLinkValueOperation(
    projectId: ProjectId,
    variable: LinkVariable,
    existingValue: ExistingLinkValue?,
    newValue: URI?,
): ValueOperation? {
  if (newValue != null) {
    val newValueDetails = LinkValueDetails(newValue, title = existingValue?.value?.title)
    return AppendValueOperation(
        NewLinkValue(
            BaseVariableValueProperties(null, projectId, 0, variable.id, null, null),
            newValueDetails,
        )
    )
  } else if (existingValue != null) {
    return DeleteValueOperation(projectId, existingValue.id)
  } else {
    // Both existing and new are null. No-op
    return null
  }
}

fun updateSelectValueOperation(
    projectId: ProjectId,
    variable: SelectVariable,
    existingValue: ExistingSelectValue?,
    newValue: Set<String>,
): ValueOperation? {
  val options =
      newValue.map { selection ->
        variable.options.firstOrNull { it.name == selection }
            ?: throw IllegalStateException(
                "Selection $selection not recognized as an option for variable ${variable.name} " +
                    "with stable ID ${variable.stableId}"
            )
      }
  val optionIds = options.map { it.id }.toSet()

  if (existingValue == null || existingValue.value.isEmpty()) {
    return if (optionIds.isNotEmpty()) {
      AppendValueOperation(
          NewSelectValue(
              BaseVariableValueProperties(null, projectId, 0, variable.id, null, null),
              optionIds,
          )
      )
    } else {
      // Before and after are both empty. No-op
      null
    }
  } else {
    return if (optionIds.isNotEmpty()) {
      UpdateValueOperation(existingValue.copy(value = optionIds))
    } else {
      // No option selected. Remove.
      DeleteValueOperation(projectId, existingValue.id)
    }
  }
}
