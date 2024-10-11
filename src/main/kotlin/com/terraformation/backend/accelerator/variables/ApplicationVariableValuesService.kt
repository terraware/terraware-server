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

    private val applicationVariablesStableIds =
        (listOf(
                StableId.CONTACT_EMAIL,
                StableId.CONTACT_NAME,
                StableId.COUNTRY,
                StableId.NUM_SPECIES,
                StableId.PROJECT_TYPE,
                StableId.TOTAL_EXPANSION_POTENTIAL,
                StableId.WEBSITE,
            ) + StableId.landUseHectaresByLandUseModel.values)
            .map { it.value }
  }

  private val variablesById: Map<VariableId, Variable> by lazy {
    applicationVariablesStableIds
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

  fun fetchValues(projectId: ProjectId): ApplicationVariableValues {
    requirePermissions { readProjectDeliverables(projectId) }

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

    val countryCode =
        getSingleSelectValue(variablesById, valuesByStableId, StableId.COUNTRY)?.let {
          // This depends on the countries table name field matching up to the select values of the
          // country variable
          val countryRow = countriesDao.fetchOneByName(it)
          if (countryRow == null) {
            log.error("Found unknown country name $it for project $projectId")
          }
          countryRow?.code
        }

    val landUseHectares =
        StableId.landUseHectaresByLandUseModel
            .mapNotNull { (landUseType, stableId) ->
              getNumberValue(valuesByStableId, stableId)?.let { landUseType to it }
            }
            .toMap()

    val numSpeciesToBePlanted = getNumberValue(valuesByStableId, StableId.NUM_SPECIES)?.toInt()

    val projectType =
        getSingleSelectValue(variablesById, valuesByStableId, StableId.PROJECT_TYPE)?.let {
            projectTypeString ->
          try {
            PreScreenProjectType.valueOf(projectTypeString)
          } catch (e: IllegalArgumentException) {
            log.error("Found unknown project type $projectTypeString for project $projectId")
            null
          }
        }

    val contactEmail = getTextValue(valuesByStableId, StableId.CONTACT_EMAIL)
    val contactName = getTextValue(valuesByStableId, StableId.CONTACT_NAME)
    val totalExpansionPotential =
        getNumberValue(valuesByStableId, StableId.TOTAL_EXPANSION_POTENTIAL)
    val website = getTextValue(valuesByStableId, StableId.WEBSITE)

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
        variablesByStableId[StableId.COUNTRY] as? SelectVariable
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
