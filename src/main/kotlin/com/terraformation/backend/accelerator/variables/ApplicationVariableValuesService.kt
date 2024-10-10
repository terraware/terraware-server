package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.accelerator.model.ApplicationVariableValues
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.CountryNotFoundException
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.SelectValue
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.springframework.beans.factory.annotation.Value

@Named
class ApplicationVariableValuesService(
    private val countriesDao: CountriesDao,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
    private val systemUser: SystemUser,
    @Value("102") // From deliverables spreadsheet
    val preScreenDeliverableId: DeliverableId,
) {
  companion object {
    private val log = perClassLogger()

    private val APPLICATION_STABLE_IDS =
        listOf(
            STABLE_ID_CONTACT_EMAIL,
            STABLE_ID_CONTACT_NAME,
            STABLE_ID_COUNTRY,
            STABLE_ID_NUM_SPECIES,
            STABLE_ID_PROJECT_TYPE,
            STABLE_ID_TOTAL_EXPANSION_POTENTIAL,
            STABLE_ID_WEBSITE,
        ) + stableIdsByLandUseModelType.values
  }

  private val variablesById: Map<VariableId, Variable> by lazy {
    APPLICATION_STABLE_IDS.map {
          variableStore.fetchByStableId(it)
              ?: throw IllegalStateException("No variable with stable ID $it")
        }
        .associateBy { it.id }
  }

  private val variablesByStableId: Map<String, Variable> by lazy {
    variablesById.values.associateBy { it.stableId }
  }

  fun fetchValues(projectId: ProjectId): ApplicationVariableValues {
    requirePermissions { readProjectDeliverables(projectId) }

    val valuesByStableId: Map<String, ExistingValue> =
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
        getSingleSelectValue(variablesById, valuesByStableId, STABLE_ID_COUNTRY)?.let {
          // This depends on the countries table name field matching up to the select values of the
          // country variable
          val countryRow = countriesDao.fetchOneByName(it)
          if (countryRow == null) {
            log.error("Found unknown country name $it for project $projectId")
          }
          countryRow?.code
        }

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

    val contactEmail = getTextValue(valuesByStableId, STABLE_ID_CONTACT_EMAIL)
    val contactName = getTextValue(valuesByStableId, STABLE_ID_CONTACT_NAME)
    val totalExpansionPotential =
        getNumberValue(valuesByStableId, STABLE_ID_TOTAL_EXPANSION_POTENTIAL)
    val website = getTextValue(valuesByStableId, STABLE_ID_WEBSITE)

    return ApplicationVariableValues(
        contactEmail = contactEmail,
        contactName = contactName,
        countryCode = countryCode,
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
        variablesByStableId[STABLE_ID_COUNTRY] as? SelectVariable
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
}
