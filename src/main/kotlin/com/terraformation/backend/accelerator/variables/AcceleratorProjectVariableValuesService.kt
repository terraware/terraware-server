package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.accelerator.model.ProjectAcceleratorVariableValuesModel
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
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
import com.terraformation.backend.documentproducer.model.StableId
import com.terraformation.backend.documentproducer.model.StableIds
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
            StableIds.accumulationRate,
            StableIds.annualCarbon,
            StableIds.applicationRestorableLand,
            StableIds.carbonCapacity,
            StableIds.clickUpLink,
            StableIds.country,
            StableIds.dealDescription,
            StableIds.dealName,
            StableIds.expectedMarketCredits,
            StableIds.failureRisk,
            StableIds.gisReportsLink,
            StableIds.investmentThesis,
            StableIds.landUseModelType,
            StableIds.maxCarbonAccumulation,
            StableIds.methodologyNumber,
            StableIds.minCarbonAccumulation,
            StableIds.minProjectArea,
            StableIds.numSpecies,
            StableIds.perHectareEstimatedBudget,
            StableIds.projectArea,
            StableIds.riskTrackerLink,
            StableIds.sdgList,
            StableIds.slackLink,
            StableIds.standard,
            StableIds.tfRestorableLand,
            StableIds.totalCarbon,
            StableIds.totalVCU,
            StableIds.totalExpansionPotential,
            StableIds.verraLink,
            StableIds.whatNeedsToBeTrue,
        ) + StableIds.landUseHectaresByLandUseModel.values
  }

  private val variablesById: Map<VariableId, Variable> by lazy {
    val variables = variableStore.fetchListByStableIds(projectAcceleratorVariablesStableIds)

    val fetchedStableIds = variables.map { it.stableId }.toSet()
    val missingStableIds = projectAcceleratorVariablesStableIds.filter { it !in fetchedStableIds }

    if (missingStableIds.isNotEmpty()) {
      log.warn("Variables with stableIds in: $missingStableIds not found")
    }

    variables.associateBy { it.id }
  }

  private val variablesByStableId: Map<StableId, Variable> by lazy {
    variablesById.values.associateBy { it.stableId }
  }

  fun fetchValues(projectId: ProjectId): ProjectAcceleratorVariableValuesModel {
    requirePermissions { readProjectAcceleratorDetails(projectId) }

    val valuesByStableId: Map<StableId, ExistingValue> =
        variableValueStore
            .listValues(projectId = projectId, variableIds = variablesById.keys)
            .mapNotNull { value ->
              val stableId = variablesById[value.variableId]?.stableId
              if (stableId != null) {
                stableId to value
              } else {
                null
              }
            }
            .toMap()

    val accumulationRate = getNumberValue(valuesByStableId, StableIds.accumulationRate)
    val annualCarbon = getNumberValue(valuesByStableId, StableIds.annualCarbon)
    val applicationReforestableLand =
        getNumberValue(valuesByStableId, StableIds.applicationRestorableLand)
    val carbonCapacity = getNumberValue(valuesByStableId, StableIds.carbonCapacity)
    val clickUpLink = getLinkValue(valuesByStableId, StableIds.clickUpLink)
    val confirmedReforestableLand = getNumberValue(valuesByStableId, StableIds.tfRestorableLand)
    val countryRow =
        getSingleSelectValue(variablesById, valuesByStableId, StableIds.country)?.let { countryName
          ->
          // This depends on the countries table name field matching up to the select values of the
          // country variable
          val countryRow = countriesDao.fetchOneByName(countryName)
          if (countryRow == null) {
            log.error("Found unknown country name $countryName for project $projectId")
          }
          countryRow
        }
    val dealDescription = getTextValue(valuesByStableId, StableIds.dealDescription)
    val dealName = getTextValue(valuesByStableId, StableIds.dealName)
    val expectedMarketCredits = getNumberValue(valuesByStableId, StableIds.expectedMarketCredits)
    val failureRisk = getTextValue(valuesByStableId, StableIds.failureRisk)
    val gisReportsLink = getLinkValue(valuesByStableId, StableIds.gisReportsLink)
    val investmentThesis = getTextValue(valuesByStableId, StableIds.investmentThesis)
    val landUseModelTypes =
        getMultiSelectValue(variablesById, valuesByStableId, StableIds.landUseModelType)
            ?.mapNotNull { landUseType ->
              try {
                LandUseModelType.forJsonValue(landUseType)
              } catch (e: IllegalArgumentException) {
                log.error("Found unknown land use model type $landUseType for project $projectId")
                null
              }
            }
            ?.toSet() ?: emptySet()
    val landUseHectares =
        StableIds.landUseHectaresByLandUseModel
            .mapNotNull { (landUseType, stableId) ->
              getNumberValue(valuesByStableId, stableId)?.let { landUseType to it }
            }
            .filter { (landUseType, _) -> landUseType in landUseModelTypes }
            .toMap()
    val maxCarbonAccumulation = getNumberValue(valuesByStableId, StableIds.maxCarbonAccumulation)
    val methodologyNumber =
        getSingleSelectValue(variablesById, valuesByStableId, StableIds.methodologyNumber)
    val minCarbonAccumulation = getNumberValue(valuesByStableId, StableIds.minCarbonAccumulation)
    val minProjectArea = getNumberValue(valuesByStableId, StableIds.minProjectArea)
    val numNativeSpecies = getNumberValue(valuesByStableId, StableIds.numSpecies)?.toInt()
    val perHectareBudget = getNumberValue(valuesByStableId, StableIds.perHectareEstimatedBudget)
    val projectArea = getNumberValue(valuesByStableId, StableIds.projectArea)
    val riskTrackerLink = getLinkValue(valuesByStableId, StableIds.riskTrackerLink)
    val sdgList =
        getMultiSelectValue(variablesById, valuesByStableId, StableIds.sdgList)
            ?.mapNotNull { sdg ->
              try {
                SustainableDevelopmentGoal.forJsonValue(sdg)
              } catch (e: IllegalArgumentException) {
                log.error("Found unknown sdg $sdg for project $projectId")
                null
              }
            }
            ?.toSet() ?: emptySet()
    val slackLink = getLinkValue(valuesByStableId, StableIds.slackLink)
    val standard = getSingleSelectValue(variablesById, valuesByStableId, StableIds.standard)
    val totalCarbon = getNumberValue(valuesByStableId, StableIds.totalCarbon)
    val totalExpansionPotential =
        getNumberValue(valuesByStableId, StableIds.totalExpansionPotential)
    val totalVCU = getNumberValue(valuesByStableId, StableIds.totalVCU)
    val verraLink = getLinkValue(valuesByStableId, StableIds.verraLink)
    val whatNeedsToBeTrue = getTextValue(valuesByStableId, StableIds.whatNeedsToBeTrue)

    return ProjectAcceleratorVariableValuesModel(
        accumulationRate = accumulationRate,
        annualCarbon = annualCarbon,
        applicationReforestableLand = applicationReforestableLand,
        carbonCapacity = carbonCapacity,
        clickUpLink = clickUpLink,
        confirmedReforestableLand = confirmedReforestableLand,
        countryCode = countryRow?.code,
        dealDescription = dealDescription,
        dealName = dealName,
        expectedMarketCredits = expectedMarketCredits,
        failureRisk = failureRisk,
        gisReportsLink = gisReportsLink,
        investmentThesis = investmentThesis,
        landUseModelTypes = landUseModelTypes,
        landUseModelHectares = landUseHectares,
        maxCarbonAccumulation = maxCarbonAccumulation,
        methodologyNumber = methodologyNumber,
        minCarbonAccumulation = minCarbonAccumulation,
        minProjectArea = minProjectArea,
        numNativeSpecies = numNativeSpecies,
        perHectareBudget = perHectareBudget,
        projectId = projectId,
        projectArea = projectArea,
        riskTrackerLink = riskTrackerLink,
        sdgList = sdgList,
        slackLink = slackLink,
        standard = standard,
        region = countryRow?.regionId,
        totalCarbon = totalCarbon,
        totalVCU = totalVCU,
        totalExpansionPotential = totalExpansionPotential,
        verraLink = verraLink,
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
              val stableId = variablesById[value.variableId]?.stableId
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

    if (existing.dealName != model.dealName) {
      updateTextValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.dealName) as TextVariable,
              valuesByStableId[StableIds.dealName] as? ExistingTextValue,
              model.dealName,
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
      val landUseModelTypesSelectValue = model.landUseModelTypes.map { it.jsonValue }.toSet()

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
