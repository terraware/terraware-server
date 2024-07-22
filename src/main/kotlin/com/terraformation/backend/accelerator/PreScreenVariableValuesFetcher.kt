package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.accelerator.model.PreScreenVariableValues
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.ExistingNumberValue
import com.terraformation.backend.documentproducer.model.ExistingSelectValue
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.math.BigDecimal

@Named
class PreScreenVariableValuesFetcher(
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
) {
  companion object {
    const val STABLE_ID_NUM_SPECIES = "22"
    const val STABLE_ID_PROJECT_TYPE = "3"

    val stableIdsByLandUseModelType =
        mapOf(
            LandUseModelType.Agroforestry to "15",
            LandUseModelType.Mangroves to "13",
            LandUseModelType.Monoculture to "7",
            LandUseModelType.NativeForest to "5",
            LandUseModelType.OtherLandUseModel to "19",
            LandUseModelType.OtherTimber to "11",
            LandUseModelType.Silvopasture to "17",
            LandUseModelType.SustainableTimber to "9",
        )

    val preScreenDeliverableId = DeliverableId(22)

    private val log = perClassLogger()
  }

  fun fetchValues(projectId: ProjectId): PreScreenVariableValues {
    requirePermissions { readProjectDeliverables(projectId) }

    val variablesById =
        variableStore.fetchDeliverableVariables(preScreenDeliverableId).associateBy { it.id }
    val valuesByStableId: Map<String, ExistingValue> =
        variableValueStore
            .listValues(projectId, preScreenDeliverableId)
            .mapNotNull { value ->
              val stableId = variablesById[value.variableId]?.stableId
              if (stableId != null) {
                stableId to value
              } else {
                null
              }
            }
            .toMap()

    val landUseHectares =
        stableIdsByLandUseModelType
            .mapNotNull { (landUseType, stableId) ->
              getNumberValue(valuesByStableId, stableId)?.let { landUseType to it }
            }
            .toMap()

    val numSpeciesToBePlanted = getNumberValue(valuesByStableId, STABLE_ID_NUM_SPECIES)?.toInt()

    val projectType =
        getSingleSelectValue(variablesById, valuesByStableId, STABLE_ID_PROJECT_TYPE)?.let {
            projectTypeString ->
          try {
            PreScreenProjectType.valueOf(projectTypeString)
          } catch (e: IllegalArgumentException) {
            log.error("Found unknown project type $projectTypeString for project $projectId")
            null
          }
        }

    return PreScreenVariableValues(landUseHectares, numSpeciesToBePlanted, projectType)
  }

  private fun getNumberValue(values: Map<String, ExistingValue>, stableId: String): BigDecimal? {
    return (values[stableId] as? ExistingNumberValue)?.value
  }

  private fun getSingleSelectValue(
      variables: Map<VariableId, Variable>,
      values: Map<String, ExistingValue>,
      stableId: String,
  ): String? {
    val selectValue = values[stableId] as? ExistingSelectValue ?: return null
    val variable = variables[selectValue.variableId] as? SelectVariable ?: return null
    val selectOptionId = selectValue.value.firstOrNull() ?: return null

    return variable.options.firstOrNull { it.id == selectOptionId }?.name
  }
}
