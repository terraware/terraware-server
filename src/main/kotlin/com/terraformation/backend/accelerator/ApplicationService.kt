package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.model.ApplicationSubmissionResult
import com.terraformation.backend.accelerator.model.ApplicationVariableValues
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.variables.ApplicationVariableValuesService
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.calculateAreaHectares
import jakarta.inject.Named
import java.net.URI
import org.locationtech.jts.geom.Geometry

@Named
class ApplicationService(
    private val applicationStore: ApplicationStore,
    private val applicationVariableValuesService: ApplicationVariableValuesService,
    private val config: TerrawareServerConfig,
    private val countryDetector: CountryDetector,
    private val hubSpotService: HubSpotService,
    private val preScreenBoundarySubmissionFetcher: PreScreenBoundarySubmissionFetcher,
    private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  /**
   * Submits an application. If the submission is for pre-screening, looks up the values of the
   * variables that affect the eligibility checks.
   *
   * The variable fetching happens here rather than directly in [ApplicationStore] to avoid adding a
   * peer dependency between store classes, since [ApplicationVariableValuesService] depends on the
   * variable stores.
   */
  fun submit(applicationId: ApplicationId): ApplicationSubmissionResult {
    requirePermissions { updateApplicationSubmissionStatus(applicationId) }

    val existing = applicationStore.fetchOneById(applicationId)
    val projectId = existing.projectId
    val variableValues = applicationVariableValuesService.fetchValues(projectId)

    return if (existing.status == ApplicationStatus.NotSubmitted) {
      val boundarySubmission = preScreenBoundarySubmissionFetcher.fetchSubmission(projectId)
      val result = applicationStore.submit(applicationId, variableValues, boundarySubmission)
      if (result.isSuccessful) {
        createProjectAcceleratorDetails(result.application, variableValues)
      }

      result
    } else {
      val result = applicationStore.submit(applicationId)
      val details = systemUser.run { projectAcceleratorDetailsService.fetchOneById(projectId) }

      if (
          config.hubSpot.enabled &&
              result.isSuccessful &&
              result.application.status == ApplicationStatus.Submitted &&
              details.hubSpotUrl == null
      ) {
        try {
          val application = result.application

          val dealPage =
              hubSpotService.createApplicationObjects(
                  applicationReforestableLand = details.applicationReforestableLand,
                  companyName = application.organizationName,
                  contactEmail = variableValues.contactEmail,
                  contactName = variableValues.contactName,
                  countryCode = application.countryCode,
                  dealName = application.internalName,
                  website = variableValues.website,
              )

          updateHubSpotUrl(application, dealPage)
        } catch (e: Exception) {
          log.error("Failed to add application data to HubSpot", e)
        }
      }

      result
    }
  }

  /**
   * Updates the application boundary, and sets the country variable if the boundary falls within
   * one country.
   */
  fun updateBoundary(applicationId: ApplicationId, boundary: Geometry) {
    val existing = applicationStore.fetchOneById(applicationId)
    applicationStore.updateBoundary(applicationId, boundary)

    val countries = countryDetector.getCountries(boundary)

    if (countries.size == 1) {
      val countryCode = countries.single()
      applicationVariableValuesService.updateCountryVariable(existing.projectId, countryCode)
      applicationStore.updateCountryCode(applicationId, countryCode)
    } else {
      log.debug(
          "Not setting internal name for application $applicationId because boundary is not " +
              "all in one country: $countries"
      )
    }
  }

  private fun updateHubSpotUrl(application: ExistingApplicationModel, url: URI) {
    systemUser.run {
      projectAcceleratorDetailsService.update(application.projectId) { it.copy(hubSpotUrl = url) }
    }
  }

  /** Populates the project accelerator details when an application passes pre-screening. */
  private fun createProjectAcceleratorDetails(
      application: ExistingApplicationModel,
      variableValues: ApplicationVariableValues,
  ) {
    val landUseModelTypes =
        variableValues.landUseModelHectares.filterValues { it.signum() > 0 }.keys
    val countryCode = variableValues.countryCode

    val dealName = variableValues.dealName ?: application.internalName

    val boundaryAreaHectares = application.boundary?.calculateAreaHectares()
    val totalLandUseHectares = variableValues.landUseModelHectares.values.sumOf { it }

    systemUser.run {
      projectAcceleratorDetailsService.update(application.projectId) { model ->
        model.copy(
            applicationReforestableLand = boundaryAreaHectares ?: totalLandUseHectares,
            countryCode = countryCode,
            dealName = dealName,
            fileNaming = application.internalName,
            landUseModelTypes = landUseModelTypes,
            numNativeSpecies = variableValues.numSpeciesToBePlanted,
            totalExpansionPotential = variableValues.totalExpansionPotential,
        )
      }
    }
  }
}
