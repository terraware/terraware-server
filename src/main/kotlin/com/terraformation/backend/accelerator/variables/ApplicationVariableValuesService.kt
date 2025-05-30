package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.accelerator.model.ApplicationVariableValues
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.CountryNotFoundException
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.SelectValue
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.documentproducer.model.StableIds
import com.terraformation.backend.documentproducer.model.TextVariable
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named

@Named
class ApplicationVariableValuesService(
    private val countriesDao: CountriesDao,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
    private val systemUser: SystemUser,
) {
  companion object {
    private val log = perClassLogger()

    private val applicationVariablesStableIds =
        (listOf(
            StableIds.contactEmail,
            StableIds.contactName,
            StableIds.country,
            StableIds.dealName,
            StableIds.numSpecies,
            StableIds.projectType,
            StableIds.totalExpansionPotential,
            StableIds.website,
        ) + StableIds.landUseHectaresByLandUseModel.values)
  }

  private val variablesById: Map<VariableId, Variable> by lazy {
    applicationVariablesStableIds
        .mapNotNull {
          val variable = variableStore.fetchByStableId(it)
          if (variable == null) {
            log.warn("Variable with stableId=${it.value} not found")
          }
          variable
        }
        .associateBy { it.id }
  }

  private val variablesByStableId: Map<StableId, Variable> by lazy {
    variablesById.values.associateBy { it.stableId }
  }

  fun fetchValues(projectId: ProjectId): ApplicationVariableValues {
    requirePermissions { readProjectDeliverables(projectId) }

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

    val countryCode =
        getSingleSelectValue(variablesById, valuesByStableId, StableIds.country)?.let {
          // This depends on the countries table name field matching up to the select values of the
          // country variable
          val countryRow = countriesDao.fetchOneByName(it)
          if (countryRow == null) {
            log.error("Found unknown country name $it for project $projectId")
          }
          countryRow?.code
        }

    val landUseHectares =
        StableIds.landUseHectaresByLandUseModel
            .mapNotNull { (landUseType, stableId) ->
              getNumberValue(valuesByStableId, stableId)?.let { landUseType to it }
            }
            .toMap()

    val numSpeciesToBePlanted = getNumberValue(valuesByStableId, StableIds.numSpecies)?.toInt()

    val projectType =
        getSingleSelectValue(variablesById, valuesByStableId, StableIds.projectType)?.let {
            projectTypeString ->
          try {
            PreScreenProjectType.valueOf(projectTypeString)
          } catch (e: IllegalArgumentException) {
            log.error("Found unknown project type $projectTypeString for project $projectId")
            null
          }
        }

    val contactEmail = getTextValue(valuesByStableId, StableIds.contactEmail)
    val contactName = getTextValue(valuesByStableId, StableIds.contactName)
    val dealName = getTextValue(valuesByStableId, StableIds.dealName)
    val totalExpansionPotential =
        getNumberValue(valuesByStableId, StableIds.totalExpansionPotential)
    val website = getTextValue(valuesByStableId, StableIds.website)

    return ApplicationVariableValues(
        contactEmail = contactEmail,
        contactName = contactName,
        countryCode = countryCode,
        dealName = dealName,
        landUseModelHectares = landUseHectares,
        numSpeciesToBePlanted = numSpeciesToBePlanted,
        projectType = projectType,
        totalExpansionPotential = totalExpansionPotential,
        website = website,
    )
  }

  /** Update country variable for a project. */
  fun updateCountryVariable(projectId: ProjectId, countryCode: String) {
    val countryVariable =
        variablesByStableId[StableIds.country] as? SelectVariable
            ?: throw IllegalStateException("Country variable stable ID not configured correctly")

    val countryName =
        countriesDao.fetchOneByCode(countryCode)?.name
            ?: throw CountryNotFoundException(countryCode)

    val existingCountryValue =
        variableValueStore
            .listValues(projectId = projectId, variableIds = listOf(countryVariable.id))
            .singleOrNull() as? SelectValue

    val operation =
        updateSelectValueOperation(
            projectId,
            countryVariable,
            existingCountryValue,
            setOf(countryName),
        )

    systemUser.run {
      // Uses elevated permission to update variables without trigger workflow
      operation?.let { variableValueStore.updateValues(listOf(it), false) }
    }
  }

  /** Update deal name variable for a project. */
  fun updateDealName(projectId: ProjectId, dealName: String) {
    val dealNameVariable =
        variablesByStableId[StableIds.dealName] as? TextVariable
            ?: throw IllegalStateException("Deal name variable stable ID not configured correctly")

    val operation =
        updateTextValueOperation(
            projectId,
            dealNameVariable,
            null,
            dealName,
        )

    systemUser.run {
      // Uses elevated permission to update variables without trigger workflow
      operation?.let { variableValueStore.updateValues(listOf(it), false) }
    }
  }
}
