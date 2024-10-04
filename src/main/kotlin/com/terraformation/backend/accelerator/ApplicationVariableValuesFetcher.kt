package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.model.ApplicationVariableValues
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.CountryNotFoundException
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.AppendValueOperation
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.ExistingNumberValue
import com.terraformation.backend.documentproducer.model.ExistingSelectValue
import com.terraformation.backend.documentproducer.model.ExistingTextValue
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.NewSelectValue
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.documentproducer.model.Variable
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.math.BigDecimal
import org.springframework.beans.factory.annotation.Value

@Named
class ApplicationVariableValuesFetcher(
    private val countriesDao: CountriesDao,
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
    private val systemUser: SystemUser,
    @Value("102") // From deliverables spreadsheet
    val preScreenDeliverableId: DeliverableId,
) {
  companion object {
    const val STABLE_ID_CONTACT_EMAIL = "26"
    const val STABLE_ID_CONTACT_NAME = "25"
    const val STABLE_ID_COUNTRY = "1"
    const val STABLE_ID_NUM_SPECIES = "22"
    const val STABLE_ID_PROJECT_TYPE = "3"
    const val STABLE_ID_TOTAL_EXPANSION_POTENTIAL = "24"
    const val STABLE_ID_WEBSITE = "27"

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

    private val log = perClassLogger()
  }

  private val variablesById: Map<VariableId, Variable> by lazy {
    (listOf(
            STABLE_ID_CONTACT_EMAIL,
            STABLE_ID_CONTACT_NAME,
            STABLE_ID_COUNTRY,
            STABLE_ID_NUM_SPECIES,
            STABLE_ID_PROJECT_TYPE,
            STABLE_ID_TOTAL_EXPANSION_POTENTIAL,
            STABLE_ID_WEBSITE,
        ) + stableIdsByLandUseModelType.values)
        .map {
          variableStore.fetchByStableId(it)
              ?: throw IllegalStateException("No variable with stable ID $it")
        }
        .associateBy { it.id }
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
        variablesById.values.firstOrNull { it.stableId == STABLE_ID_COUNTRY } as? SelectVariable
            ?: throw IllegalStateException("Country variable stable ID not configured correctly")

    val countryName =
        countriesDao.fetchOneByCode(countryCode)?.name
            ?: throw CountryNotFoundException(countryCode)
    val selectOption =
        countryVariable.options.firstOrNull { it.name == countryName }
            ?: throw IllegalStateException("Country $countryName select option not recognized")
    val selectValue =
        NewSelectValue(
            BaseVariableValueProperties(null, projectId, 0, countryVariable.id, null, null),
            setOf(selectOption.id))

    systemUser.run {
      // Uses elevated permission to update variables without trigger workflow
      variableValueStore.updateValues(listOf(AppendValueOperation(selectValue)), false)
    }
  }

  private fun getNumberValue(values: Map<String, ExistingValue>, stableId: String): BigDecimal? {
    return (values[stableId] as? ExistingNumberValue)?.value
  }

  private fun getTextValue(values: Map<String, ExistingValue>, stableId: String): String? {
    return (values[stableId] as? ExistingTextValue)?.value
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
