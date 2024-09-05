package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.model.ApplicationSubmissionResult
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.model.PreScreenVariableValues
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.tables.daos.DefaultProjectLeadsDao
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.util.calculateAreaHectares
import jakarta.inject.Named

@Named
class ApplicationService(
    private val applicationStore: ApplicationStore,
    private val countriesDao: CountriesDao,
    private val countryDetector: CountryDetector,
    private val defaultProjectLeadsDao: DefaultProjectLeadsDao,
    private val preScreenVariableValuesFetcher: PreScreenVariableValuesFetcher,
    private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore,
    private val systemUser: SystemUser,
) {
  /**
   * Submits an application. If the submission is for pre-screening, looks up the values of the
   * variables that affect the eligibility checks.
   *
   * The variable fetching happens here rather than directly in [ApplicationStore] to avoid adding a
   * peer dependency between store classes, since [PreScreenVariableValuesFetcher] depends on the
   * variable stores.
   */
  fun submit(applicationId: ApplicationId): ApplicationSubmissionResult {
    requirePermissions { updateApplicationSubmissionStatus(applicationId) }

    val existing = applicationStore.fetchOneById(applicationId)

    return if (existing.status == ApplicationStatus.NotSubmitted) {
      val variableValues = preScreenVariableValuesFetcher.fetchValues(existing.projectId)
      val result = applicationStore.submit(applicationId, variableValues)
      if (result.isSuccessful) {
        createProjectAcceleratorDetails(result.application, variableValues)
      }

      result
    } else {
      applicationStore.submit(applicationId)
    }
  }

  /** Populates the project accelerator details when an application passes pre-screening. */
  private fun createProjectAcceleratorDetails(
      application: ExistingApplicationModel,
      variableValues: PreScreenVariableValues
  ) {
    val landUseModelTypes =
        variableValues.landUseModelHectares.filterValues { it.signum() > 0 }.keys
    val countryCode = variableValues.countryCode
    val region = countryCode?.let { countriesDao.fetchOneByCode(it) }?.regionId
    val projectLead = region?.let { defaultProjectLeadsDao.fetchOneByRegionId(it) }?.projectLead

    systemUser.run {
      projectAcceleratorDetailsStore.update(application.projectId) { model ->
        model.copy(
            applicationReforestableLand = application.boundary?.calculateAreaHectares(),
            countryCode = countryCode,
            fileNaming = application.internalName,
            landUseModelTypes = landUseModelTypes,
            numNativeSpecies = variableValues.numSpeciesToBePlanted,
            projectLead = projectLead,
            totalExpansionPotential = variableValues.totalExpansionPotential,
        )
      }
    }
  }
}
