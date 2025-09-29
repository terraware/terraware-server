package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.accelerator.model.CarbonCertification
import com.terraformation.backend.accelerator.model.ProjectAcceleratorVariableValuesModel
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.accelerator.model.startingDigitRegex
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.ExistingLinkValue
import com.terraformation.backend.documentproducer.model.ExistingNumberValue
import com.terraformation.backend.documentproducer.model.ExistingSelectValue
import com.terraformation.backend.documentproducer.model.ExistingTextValue
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.LinkVariable
import com.terraformation.backend.documentproducer.model.NumberVariable
import com.terraformation.backend.documentproducer.model.SelectVariable
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
            StableIds.carbonCertifications,
            StableIds.clickUpLink,
            StableIds.country,
            StableIds.dealDescription,
            StableIds.dealName,
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
            StableIds.projectHighlightPhoto,
            StableIds.projectZoneFigure,
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
    val carbonCertifications =
        getMultiSelectValue(variablesById, valuesByStableId, StableIds.carbonCertifications)
            ?.map { certification -> CarbonCertification.forDisplayName(certification) }
            ?.toSet() ?: emptySet()
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
            .toMap()
    val maxCarbonAccumulation = getNumberValue(valuesByStableId, StableIds.maxCarbonAccumulation)
    val methodologyNumber =
        getSingleSelectValue(variablesById, valuesByStableId, StableIds.methodologyNumber)
    val minCarbonAccumulation = getNumberValue(valuesByStableId, StableIds.minCarbonAccumulation)
    val minProjectArea = getNumberValue(valuesByStableId, StableIds.minProjectArea)
    val numNativeSpecies = getNumberValue(valuesByStableId, StableIds.numSpecies)?.toInt()
    val perHectareBudget = getNumberValue(valuesByStableId, StableIds.perHectareEstimatedBudget)
    val projectArea = getNumberValue(valuesByStableId, StableIds.projectArea)
    val projectHighlightPhotoValueId = getValueId(valuesByStableId, StableIds.projectHighlightPhoto)
    val projectZoneFigureValueId = getValueId(valuesByStableId, StableIds.projectZoneFigure)
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
        carbonCertifications = carbonCertifications,
        clickUpLink = clickUpLink,
        confirmedReforestableLand = confirmedReforestableLand,
        countryAlpha3 = countryRow?.codeAlpha3,
        countryCode = countryRow?.code,
        dealDescription = dealDescription,
        dealName = dealName,
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
        projectArea = projectArea,
        projectHighlightPhotoValueId = projectHighlightPhotoValueId,
        projectId = projectId,
        projectZoneFigureValueId = projectZoneFigureValueId,
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

    if (existing.accumulationRate != model.accumulationRate) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.accumulationRate) as NumberVariable,
              valuesByStableId[StableIds.accumulationRate] as? ExistingNumberValue,
              model.accumulationRate,
          )
          ?.let { operations.add(it) }
    }

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

    if (existing.carbonCertifications != model.carbonCertifications) {
      val variable = getVariableByStableId(StableIds.carbonCertifications) as SelectVariable
      val certificationsSet = model.carbonCertifications.map { it.displayName }.toSet()

      val certificationsSelectValue =
          certificationsSet
              .mapNotNull { certification ->
                variable.options.find { it.name == certification }?.name
              }
              .toSet()

      updateSelectValueOperation(
              projectId = projectId,
              variable,
              valuesByStableId[StableIds.carbonCertifications] as? ExistingSelectValue,
              certificationsSelectValue,
          )
          ?.let { operations.add(it) }
    }

    if (existing.clickUpLink != model.clickUpLink) {
      updateLinkValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.clickUpLink) as LinkVariable,
              valuesByStableId[StableIds.clickUpLink] as? ExistingLinkValue,
              model.clickUpLink,
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

    if (existing.gisReportsLink != model.gisReportsLink) {
      updateLinkValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.gisReportsLink) as LinkVariable,
              valuesByStableId[StableIds.gisReportsLink] as? ExistingLinkValue,
              model.gisReportsLink,
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

    if (existing.landUseModelHectares != model.landUseModelHectares) {
      StableIds.landUseHectaresByLandUseModel.forEach { (type, stableId) ->
        val newHectares = model.landUseModelHectares[type]
        updateNumberValueOperation(
                projectId = projectId,
                getVariableByStableId(stableId) as NumberVariable,
                valuesByStableId[stableId] as? ExistingNumberValue,
                newHectares,
            )
            ?.let { operations.add(it) }
      }
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

    if (existing.methodologyNumber != model.methodologyNumber) {
      updateSelectValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.methodologyNumber) as SelectVariable,
              valuesByStableId[StableIds.methodologyNumber] as? ExistingSelectValue,
              setOfNotNull(model.methodologyNumber),
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

    if (existing.minProjectArea != model.minProjectArea) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.minProjectArea) as NumberVariable,
              valuesByStableId[StableIds.minProjectArea] as? ExistingNumberValue,
              model.minProjectArea,
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

    if (existing.projectArea != model.projectArea) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.projectArea) as NumberVariable,
              valuesByStableId[StableIds.projectArea] as? ExistingNumberValue,
              model.projectArea,
          )
          ?.let { operations.add(it) }
    }

    if (existing.riskTrackerLink != model.riskTrackerLink) {
      updateLinkValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.riskTrackerLink) as LinkVariable,
              valuesByStableId[StableIds.riskTrackerLink] as? ExistingLinkValue,
              model.riskTrackerLink,
          )
          ?.let { operations.add(it) }
    }

    if (existing.sdgList != model.sdgList) {
      val variable = getVariableByStableId(StableIds.sdgList) as SelectVariable
      val sdgSet = model.sdgList.map { it.sdgNumber }.toSet()

      val sdgSelectValue =
          sdgSet
              .mapNotNull { sdg ->
                variable.options
                    .find { startingDigitRegex.find(it.name)?.value?.toInt() == sdg }
                    ?.name
              }
              .toSet()

      updateSelectValueOperation(
              projectId = projectId,
              variable,
              valuesByStableId[StableIds.sdgList] as? ExistingSelectValue,
              sdgSelectValue,
          )
          ?.let { operations.add(it) }
    }

    if (existing.slackLink != model.slackLink) {
      updateLinkValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.slackLink) as LinkVariable,
              valuesByStableId[StableIds.slackLink] as? ExistingLinkValue,
              model.slackLink,
          )
          ?.let { operations.add(it) }
    }

    if (existing.standard != model.standard) {
      updateSelectValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.standard) as SelectVariable,
              valuesByStableId[StableIds.standard] as? ExistingSelectValue,
              setOfNotNull(model.standard),
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

    if (existing.totalVCU != model.totalVCU) {
      updateNumberValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.totalVCU) as NumberVariable,
              valuesByStableId[StableIds.totalVCU] as? ExistingNumberValue,
              model.totalVCU,
          )
          ?.let { operations.add(it) }
    }

    if (existing.verraLink != model.verraLink) {
      updateLinkValueOperation(
              projectId = projectId,
              getVariableByStableId(StableIds.verraLink) as LinkVariable,
              valuesByStableId[StableIds.verraLink] as? ExistingLinkValue,
              model.verraLink,
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
