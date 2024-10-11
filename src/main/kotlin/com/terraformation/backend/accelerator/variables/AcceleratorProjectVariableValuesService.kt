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
                StableId.ANNUAL_CARBON,
                StableId.APPLICATION_RESTORABLE_LAND,
                StableId.CARBON_CAPACITY,
                StableId.COUNTRY,
                StableId.DEAL_DESCRIPTION,
                StableId.FAILURE_RISK,
                StableId.INVESTMENT_THESIS,
                StableId.LAND_USE_MODEL_TYPES,
                StableId.MAX_CARBON_ACCUMULATION,
                StableId.MIN_CARBON_ACCUMULATION,
                StableId.NUM_SPECIES,
                StableId.PER_HECTARE_ESTIMATED_BUDGET,
                StableId.TF_RESTORABLE_LAND,
                StableId.TOTAL_CARBON,
                StableId.TOTAL_EXPANSION_POTENTIAL,
                StableId.WHAT_NEEDS_TO_BE_TRUE,
            )
            .map { it.value }
  }

  private val variablesById: Map<VariableId, Variable> by lazy {
    projectAcceleratorVariablesStableIds
        .map {
          variableStore.fetchByStableId(it)
              ?: throw IllegalStateException("No variable with stable ID $it")
        }
        .associateBy { it.id }
  }

  private val variablesByStableId: Map<StableId, Variable> by lazy {
    variablesById.values
        .mapNotNull { variable -> StableId.from(variable.stableId)?.let { it to variable } }
        .toMap()
  }

  fun fetchValues(projectId: ProjectId): ProjectAcceleratorVariableValuesModel {
    requirePermissions { readProjectAcceleratorDetails(projectId) }

    val valuesByStableId: Map<StableId, ExistingValue> =
        variableValueStore
            .listValues(projectId = projectId, variableIds = variablesById.keys)
            .mapNotNull { value ->
              val stableId = variablesById[value.variableId]?.let { StableId.from(it.stableId) }
              if (stableId != null) {
                stableId to value
              } else {
                null
              }
            }
            .toMap()

    val annualCarbon = getNumberValue(valuesByStableId, StableId.ANNUAL_CARBON)
    val applicationReforestableLand =
        getNumberValue(valuesByStableId, StableId.APPLICATION_RESTORABLE_LAND)
    val carbonCapacity = getNumberValue(valuesByStableId, StableId.CARBON_CAPACITY)
    val confirmedReforestableLand = getNumberValue(valuesByStableId, StableId.TF_RESTORABLE_LAND)
    val countryRow =
        getSingleSelectValue(variablesById, valuesByStableId, StableId.COUNTRY)?.let {
          // This depends on the countries table name field matching up to the select values of the
          // country variable
          val countryRow = countriesDao.fetchOneByName(it)
          if (countryRow == null) {
            log.error("Found unknown country name $it for project $projectId")
          }
          countryRow
        }
    val dealDescription = getTextValue(valuesByStableId, StableId.DEAL_DESCRIPTION)
    val failureRisk = getTextValue(valuesByStableId, StableId.FAILURE_RISK)
    val investmentThesis = getTextValue(valuesByStableId, StableId.INVESTMENT_THESIS)
    val landUseModelTypes =
        getMultiSelectValue(variablesById, valuesByStableId, StableId.LAND_USE_MODEL_TYPES)
            ?.mapNotNull {
              try {
                LandUseModelType.valueOf(it)
              } catch (e: IllegalArgumentException) {
                log.error("Found unknown land use model type $it for project $projectId")
                null
              }
            }
            ?.toSet() ?: emptySet()
    val maxCarbonAccumulation = getNumberValue(valuesByStableId, StableId.MAX_CARBON_ACCUMULATION)
    val minCarbonAccumulation = getNumberValue(valuesByStableId, StableId.MIN_CARBON_ACCUMULATION)
    val numNativeSpecies = getNumberValue(valuesByStableId, StableId.NUM_SPECIES)?.toInt()
    val perHectareBudget = getNumberValue(valuesByStableId, StableId.PER_HECTARE_ESTIMATED_BUDGET)
    val totalCarbon = getNumberValue(valuesByStableId, StableId.TOTAL_CARBON)
    val totalExpansionPotential =
        getNumberValue(valuesByStableId, StableId.TOTAL_EXPANSION_POTENTIAL)
    val whatNeedsToBeTrue = getTextValue(valuesByStableId, StableId.WHAT_NEEDS_TO_BE_TRUE)

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
              val stableId = variablesById[value.variableId]?.stableId?.let { StableId.from(it) }
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
              getVariableByStableId(StableId.ANNUAL_CARBON) as NumberVariable,
              valuesByStableId[StableId.ANNUAL_CARBON] as? ExistingNumberValue,
              model.annualCarbon,
          )
          ?.let { operations.add(it) }
    }

    if (existing.applicationReforestableLand != model.applicationReforestableLand) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.APPLICATION_RESTORABLE_LAND) as NumberVariable,
              valuesByStableId[StableId.APPLICATION_RESTORABLE_LAND] as? ExistingNumberValue,
              model.applicationReforestableLand,
          )
          ?.let { operations.add(it) }
    }

    if (existing.carbonCapacity != model.carbonCapacity) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.CARBON_CAPACITY) as NumberVariable,
              valuesByStableId[StableId.CARBON_CAPACITY] as? ExistingNumberValue,
              model.carbonCapacity,
          )
          ?.let { operations.add(it) }
    }

    if (existing.confirmedReforestableLand != model.confirmedReforestableLand) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.TF_RESTORABLE_LAND) as NumberVariable,
              valuesByStableId[StableId.TF_RESTORABLE_LAND] as? ExistingNumberValue,
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
              getVariableByStableId(StableId.COUNTRY) as SelectVariable,
              valuesByStableId[StableId.COUNTRY] as? ExistingSelectValue,
              countryNameSelectValue,
          )
          ?.let { operations.add(it) }
    }

    if (existing.dealDescription != model.dealDescription) {
      updateTextValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.DEAL_DESCRIPTION) as TextVariable,
              valuesByStableId[StableId.DEAL_DESCRIPTION] as? ExistingTextValue,
              model.dealDescription,
          )
          ?.let { operations.add(it) }
    }

    if (existing.failureRisk != model.failureRisk) {
      updateTextValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.FAILURE_RISK) as TextVariable,
              valuesByStableId[StableId.FAILURE_RISK] as? ExistingTextValue,
              model.failureRisk,
          )
          ?.let { operations.add(it) }
    }

    if (existing.investmentThesis != model.investmentThesis) {
      updateTextValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.INVESTMENT_THESIS) as TextVariable,
              valuesByStableId[StableId.INVESTMENT_THESIS] as? ExistingTextValue,
              model.investmentThesis,
          )
          ?.let { operations.add(it) }
    }

    if (existing.landUseModelTypes != model.landUseModelTypes) {
      val landUseModelTypesSelectValue = model.landUseModelTypes.map { it.name }.toSet()

      updateSelectValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.LAND_USE_MODEL_TYPES) as SelectVariable,
              valuesByStableId[StableId.LAND_USE_MODEL_TYPES] as? ExistingSelectValue,
              landUseModelTypesSelectValue,
          )
          ?.let { operations.add(it) }
    }

    if (existing.maxCarbonAccumulation != model.maxCarbonAccumulation) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.MAX_CARBON_ACCUMULATION) as NumberVariable,
              valuesByStableId[StableId.MAX_CARBON_ACCUMULATION] as? ExistingNumberValue,
              model.maxCarbonAccumulation,
          )
          ?.let { operations.add(it) }
    }

    if (existing.minCarbonAccumulation != model.minCarbonAccumulation) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.MIN_CARBON_ACCUMULATION) as NumberVariable,
              valuesByStableId[StableId.MIN_CARBON_ACCUMULATION] as? ExistingNumberValue,
              model.minCarbonAccumulation,
          )
          ?.let { operations.add(it) }
    }

    if (existing.numNativeSpecies != model.numNativeSpecies) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.NUM_SPECIES) as NumberVariable,
              valuesByStableId[StableId.NUM_SPECIES] as? ExistingNumberValue,
              model.numNativeSpecies?.toBigDecimal(),
          )
          ?.let { operations.add(it) }
    }

    if (existing.perHectareBudget != model.perHectareBudget) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.PER_HECTARE_ESTIMATED_BUDGET) as NumberVariable,
              valuesByStableId[StableId.PER_HECTARE_ESTIMATED_BUDGET] as? ExistingNumberValue,
              model.perHectareBudget,
          )
          ?.let { operations.add(it) }
    }

    if (existing.totalCarbon != model.totalCarbon) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.TOTAL_CARBON) as NumberVariable,
              valuesByStableId[StableId.TOTAL_CARBON] as? ExistingNumberValue,
              model.totalCarbon,
          )
          ?.let { operations.add(it) }
    }

    if (existing.totalExpansionPotential != model.totalExpansionPotential) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.TOTAL_EXPANSION_POTENTIAL) as NumberVariable,
              valuesByStableId[StableId.TOTAL_EXPANSION_POTENTIAL] as? ExistingNumberValue,
              model.totalExpansionPotential,
          )
          ?.let { operations.add(it) }
    }

    if (existing.whatNeedsToBeTrue != model.whatNeedsToBeTrue) {
      updateTextValueOperation(
              projectId = projectId,
              getVariableByStableId(StableId.WHAT_NEEDS_TO_BE_TRUE) as TextVariable,
              valuesByStableId[StableId.WHAT_NEEDS_TO_BE_TRUE] as? ExistingTextValue,
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
