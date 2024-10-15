package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.accelerator.model.ProjectAcceleratorVariableValuesModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.ExistingNumberValue
import com.terraformation.backend.documentproducer.model.ExistingSelectValue
import com.terraformation.backend.documentproducer.model.ExistingTextValue
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.NumberVariable
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.documentproducer.model.TextVariable
import com.terraformation.backend.documentproducer.model.ValueOperation
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named

@Named
class AcceleratorProjectVariableValuesService(
    private val countriesDao: CountriesDao,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
    private val systemUser: SystemUser,
) {
  companion object {
    private val log = perClassLogger()

    private val projectAcceleratorVariablesStableIds =
        listOf(
            StableIds.annualCarbon,
            StableIds.applicationRestorableLand,
            StableIds.carbonCapacity,
            StableIds.country,
            StableIds.dealDescription,
            StableIds.failureRisk,
            StableIds.investmentThesis,
            StableIds.landUseModelType,
            StableIds.maxCarbonAccumulation,
            StableIds.minCarbonAccumulation,
            StableIds.numSpecies,
            StableIds.perHectareEstimatedBudget,
            StableIds.tfRestorableLand,
            StableIds.totalCarbon,
            StableIds.totalExpansionPotential,
            StableIds.whatNeedsToBeTrue,
        )
  }

  private val variablesById: Map<VariableId, Variable> by lazy {
    projectAcceleratorVariablesStableIds
        .map {
          variableStore.fetchByStableId(it.value)
              ?: throw IllegalStateException("No variable with stable ID ${it.value}")
        }
        .associateBy { it.id }
  }

  private val variablesByStableId: Map<StableId, Variable> by lazy {
    variablesById.values.associateBy { StableId(it.stableId) }
  }

  fun fetchValues(projectId: ProjectId): ProjectAcceleratorVariableValuesModel {
    requirePermissions { readProjectAcceleratorDetails(projectId) }

    val valuesByStableId: Map<StableId, ExistingValue> =
        variableValueStore
            .listValues(projectId = projectId, variableIds = variablesById.keys)
            .mapNotNull { value ->
              val stableId = variablesById[value.variableId]?.let { StableId(it.stableId) }
              if (stableId != null) {
                stableId to value
              } else {
                null
              }
            }
            .toMap()

    val annualCarbon = getNumberValue(valuesByStableId, StableIds.annualCarbon)
    val applicationReforestableLand =
        getNumberValue(valuesByStableId, StableIds.applicationRestorableLand)
    val carbonCapacity = getNumberValue(valuesByStableId, StableIds.carbonCapacity)
    val confirmedReforestableLand = getNumberValue(valuesByStableId, StableIds.tfRestorableLand)
    val countryRow =
        getSingleSelectValue(variablesById, valuesByStableId, StableIds.country)?.let {
          // This depends on the countries table name field matching up to the select values of the
          // country variable
          val countryRow = countriesDao.fetchOneByName(it)
          if (countryRow == null) {
            log.error("Found unknown country name $it for project $projectId")
          }
          countryRow
        }
    val dealDescription = getTextValue(valuesByStableId, StableIds.dealDescription)
    val failureRisk = getTextValue(valuesByStableId, StableIds.failureRisk)
    val investmentThesis = getTextValue(valuesByStableId, StableIds.investmentThesis)
    val landUseModelTypes =
        getMultiSelectValue(variablesById, valuesByStableId, StableIds.landUseModelType)
            ?.mapNotNull {
              try {
                LandUseModelType.valueOf(it)
              } catch (e: IllegalArgumentException) {
                log.error("Found unknown land use model type $it for project $projectId")
                null
              }
            }
            ?.toSet() ?: emptySet()
    val maxCarbonAccumulation = getNumberValue(valuesByStableId, StableIds.maxCarbonAccumulation)
    val minCarbonAccumulation = getNumberValue(valuesByStableId, StableIds.minCarbonAccumulation)
    val numNativeSpecies = getNumberValue(valuesByStableId, StableIds.numSpecies)?.toInt()
    val perHectareBudget = getNumberValue(valuesByStableId, StableIds.perHectareEstimatedBudget)
    val totalCarbon = getNumberValue(valuesByStableId, StableIds.totalCarbon)
    val totalExpansionPotential =
        getNumberValue(valuesByStableId, StableIds.totalExpansionPotential)
    val whatNeedsToBeTrue = getTextValue(valuesByStableId, StableIds.whatNeedsToBeTrue)

    return ProjectAcceleratorVariableValuesModel(
        annualCarbon = annualCarbon,
        applicationReforestableLand = applicationReforestableLand,
        carbonCapacity = carbonCapacity,
        confirmedReforestableLand = confirmedReforestableLand,
        countryCode = countryRow?.code,
        dealDescription = dealDescription,
        failureRisk = failureRisk,
        investmentThesis = investmentThesis,
        landUseModelTypes = landUseModelTypes,
        maxCarbonAccumulation = maxCarbonAccumulation,
        minCarbonAccumulation = minCarbonAccumulation,
        numNativeSpecies = numNativeSpecies,
        perHectareBudget = perHectareBudget,
        projectId = projectId,
        region = countryRow?.regionId,
        totalCarbon = totalCarbon,
        totalExpansionPotential = totalExpansionPotential,
        whatNeedsToBeTrue = whatNeedsToBeTrue,
    )
  }

  fun writeValues(
      projectId: ProjectId,
      model: ProjectAcceleratorVariableValuesModel,
  ) {
    requirePermissions { updateProjectAcceleratorDetails(projectId) }

    val existing = fetchValues(projectId)
    val valuesByStableId: Map<StableId, ExistingValue> =
        variableValueStore
            .listValues(projectId = projectId, variableIds = variablesById.keys)
            .mapNotNull { value ->
              val stableId = variablesById[value.variableId]?.stableId?.let { StableId(it) }
              if (stableId != null) {
                stableId to value
              } else {
                null
              }
            }
            .toMap()

    val operations = mutableListOf<ValueOperation>()

    if (existing.annualCarbon != model.annualCarbon) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.annualCarbon) as NumberVariable,
              valuesByStableId[StableIds.annualCarbon] as? ExistingNumberValue,
              model.annualCarbon,
          )
          ?.let { operations.add(it) }
    }

    if (existing.applicationReforestableLand != model.applicationReforestableLand) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.applicationRestorableLand) as NumberVariable,
              valuesByStableId[StableIds.applicationRestorableLand] as? ExistingNumberValue,
              model.applicationReforestableLand,
          )
          ?.let { operations.add(it) }
    }

    if (existing.carbonCapacity != model.carbonCapacity) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.carbonCapacity) as NumberVariable,
              valuesByStableId[StableIds.carbonCapacity] as? ExistingNumberValue,
              model.carbonCapacity,
          )
          ?.let { operations.add(it) }
    }

    if (existing.confirmedReforestableLand != model.confirmedReforestableLand) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.tfRestorableLand) as NumberVariable,
              valuesByStableId[StableIds.tfRestorableLand] as? ExistingNumberValue,
              model.confirmedReforestableLand,
          )
          ?.let { operations.add(it) }
    }

    if (existing.countryCode != model.countryCode) {
      val countryNameSelectValue =
          model.countryCode?.let {
            countriesDao.fetchOneByCode(model.countryCode)?.let { row -> setOfNotNull(row.name) }
          } ?: emptySet()

      updateSelectValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.country) as SelectVariable,
              valuesByStableId[StableIds.country] as? ExistingSelectValue,
              countryNameSelectValue,
          )
          ?.let { operations.add(it) }
    }

    if (existing.dealDescription != model.dealDescription) {
      updateTextValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.dealDescription) as TextVariable,
              valuesByStableId[StableIds.dealDescription] as? ExistingTextValue,
              model.dealDescription,
          )
          ?.let { operations.add(it) }
    }

    if (existing.failureRisk != model.failureRisk) {
      updateTextValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.failureRisk) as TextVariable,
              valuesByStableId[StableIds.failureRisk] as? ExistingTextValue,
              model.failureRisk,
          )
          ?.let { operations.add(it) }
    }

    if (existing.investmentThesis != model.investmentThesis) {
      updateTextValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.investmentThesis) as TextVariable,
              valuesByStableId[StableIds.investmentThesis] as? ExistingTextValue,
              model.investmentThesis,
          )
          ?.let { operations.add(it) }
    }

    if (existing.landUseModelTypes != model.landUseModelTypes) {
      val landUseModelTypesSelectValue = model.landUseModelTypes.map { it.name }.toSet()

      updateSelectValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.landUseModelType) as SelectVariable,
              valuesByStableId[StableIds.landUseModelType] as? ExistingSelectValue,
              landUseModelTypesSelectValue,
          )
          ?.let { operations.add(it) }
    }

    if (existing.maxCarbonAccumulation != model.maxCarbonAccumulation) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.maxCarbonAccumulation) as NumberVariable,
              valuesByStableId[StableIds.maxCarbonAccumulation] as? ExistingNumberValue,
              model.maxCarbonAccumulation,
          )
          ?.let { operations.add(it) }
    }

    if (existing.minCarbonAccumulation != model.minCarbonAccumulation) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.minCarbonAccumulation) as NumberVariable,
              valuesByStableId[StableIds.minCarbonAccumulation] as? ExistingNumberValue,
              model.minCarbonAccumulation,
          )
          ?.let { operations.add(it) }
    }

    if (existing.numNativeSpecies != model.numNativeSpecies) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.numSpecies) as NumberVariable,
              valuesByStableId[StableIds.numSpecies] as? ExistingNumberValue,
              model.numNativeSpecies?.toBigDecimal(),
          )
          ?.let { operations.add(it) }
    }

    if (existing.perHectareBudget != model.perHectareBudget) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.perHectareEstimatedBudget) as NumberVariable,
              valuesByStableId[StableIds.perHectareEstimatedBudget] as? ExistingNumberValue,
              model.perHectareBudget,
          )
          ?.let { operations.add(it) }
    }

    if (existing.totalCarbon != model.totalCarbon) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.totalCarbon) as NumberVariable,
              valuesByStableId[StableIds.totalCarbon] as? ExistingNumberValue,
              model.totalCarbon,
          )
          ?.let { operations.add(it) }
    }

    if (existing.totalExpansionPotential != model.totalExpansionPotential) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.totalExpansionPotential) as NumberVariable,
              valuesByStableId[StableIds.totalExpansionPotential] as? ExistingNumberValue,
              model.totalExpansionPotential,
          )
          ?.let { operations.add(it) }
    }

    if (existing.whatNeedsToBeTrue != model.whatNeedsToBeTrue) {
      updateTextValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.whatNeedsToBeTrue) as TextVariable,
              valuesByStableId[StableIds.whatNeedsToBeTrue] as? ExistingTextValue,
              model.whatNeedsToBeTrue,
          )
          ?.let { operations.add(it) }
    }

    systemUser.run { variableValueStore.updateValues(operations, false) }
  }

  private fun getVariableByStableId(stableId: StableId): Variable {
    return variablesByStableId[stableId]
        ?: throw IllegalStateException("Variable with stable ID $stableId not found")
  }
}
