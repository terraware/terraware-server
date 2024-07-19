package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ApplicationSubmissionResult
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.accelerator.model.PreScreenVariableValues
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.accelerator.tables.references.APPLICATION_HISTORIES
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.calculateAreaHectares
import jakarta.inject.Named
import java.time.InstantSource
import kotlin.math.abs
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.locationtech.jts.geom.Geometry

@Named
class ApplicationStore(
    private val clock: InstantSource,
    private val countriesDao: CountriesDao,
    private val countryDetector: CountryDetector,
    private val dslContext: DSLContext,
    private val messages: Messages,
    private val organizationsDao: OrganizationsDao,
) {
  private val defaultMinimumHectares = 15000
  private val defaultMaximumHectares = 100000
  private val perCountryMinimumHectares =
      mapOf(
          "CO" to 3000,
          "GH" to 3000,
          "KE" to 3000,
          "TZ" to 3000,
      )

  /**
   * How far the total of the per-land-use-type hectare counts is allowed to vary from the size of
   * the project boundary without being treated as an error.
   */
  private val landUseTotalFuzzPercent = 10

  /** Maximum percentage of total land allowed to be used for monoculture. */
  private val monocultureMaxPercent = 10

  private val minimumSpeciesByProjectType =
      mapOf(
          PreScreenProjectType.Mangrove to 3,
          PreScreenProjectType.Mixed to 10,
          PreScreenProjectType.Terrestrial to 10,
      )

  private val log = perClassLogger()

  fun fetchOneById(applicationId: ApplicationId): ExistingApplicationModel {
    requirePermissions { readApplication(applicationId) }

    return fetchByCondition(APPLICATIONS.ID.eq(applicationId)).firstOrNull()
        ?: throw ApplicationNotFoundException(applicationId)
  }

  fun fetchByProjectId(projectId: ProjectId): List<ExistingApplicationModel> {
    requirePermissions { readProject(projectId) }

    return fetchByCondition(APPLICATIONS.PROJECT_ID.eq(projectId))
  }

  fun fetchByOrganizationId(organizationId: OrganizationId): List<ExistingApplicationModel> {
    requirePermissions { readOrganization(organizationId) }

    return fetchByCondition(APPLICATIONS.projects.ORGANIZATION_ID.eq(organizationId))
  }

  fun fetchAll(): List<ExistingApplicationModel> {
    requirePermissions { readAllAcceleratorDetails() }

    return fetchByCondition(DSL.trueCondition())
  }

  fun create(projectId: ProjectId): ExistingApplicationModel {
    requirePermissions { createApplication(projectId) }

    val userId = currentUser().userId
    val now = clock.instant()

    return dslContext.transactionResult { _ ->
      val applicationId =
          with(APPLICATIONS) {
            dslContext
                .insertInto(APPLICATIONS)
                .set(APPLICATION_STATUS_ID, ApplicationStatus.NotSubmitted)
                .set(CREATED_BY, userId)
                .set(CREATED_TIME, now)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .set(PROJECT_ID, projectId)
                .onConflict(PROJECT_ID)
                .doNothing()
                .returning(ID)
                .fetchOne(ID) ?: throw ProjectApplicationExistsException(projectId)
          }

      insertHistory(applicationId)

      fetchOneById(applicationId)
    }
  }

  fun review(
      applicationId: ApplicationId,
      updateFunc: (ExistingApplicationModel) -> ExistingApplicationModel
  ) {
    requirePermissions { reviewApplication(applicationId) }

    val existing = fetchOneById(applicationId)
    val modified = updateFunc(existing)

    with(APPLICATIONS) {
      dslContext
          .update(APPLICATIONS)
          .set(APPLICATION_STATUS_ID, modified.status)
          .set(FEEDBACK, modified.feedback)
          .set(INTERNAL_COMMENT, modified.internalComment)
          .where(ID.eq(applicationId))
          .execute()
    }

    updateStatus(applicationId, modified.status)
  }

  fun restart(applicationId: ApplicationId) {
    requirePermissions { updateApplicationSubmissionStatus(applicationId) }

    val existing = fetchOneById(applicationId)

    if (existing.status != ApplicationStatus.NotSubmitted) {
      updateStatus(applicationId, ApplicationStatus.NotSubmitted)
    }
  }

  /**
   * Submits an application. If it is being submitted for pre-screening, does the pre-screening
   * qualification checks.
   */
  fun submit(
      applicationId: ApplicationId,
      preScreenVariableValues: PreScreenVariableValues? = null
  ): ApplicationSubmissionResult {
    requirePermissions { updateApplicationSubmissionStatus(applicationId) }

    val existing = fetchOneById(applicationId)

    return if (existing.status == ApplicationStatus.NotSubmitted) {
      if (preScreenVariableValues == null) {
        throw IllegalArgumentException(
            "No pre-screen variable values supplied for pre-screen submission")
      }

      val problems = checkPreScreenCriteria(existing, preScreenVariableValues)

      if (problems.isNotEmpty()) {
        updateStatus(applicationId, ApplicationStatus.FailedPreScreen)
      } else {
        updateStatus(applicationId, ApplicationStatus.PassedPreScreen)
      }

      ApplicationSubmissionResult(fetchOneById(applicationId), problems)
    } else {
      log.info(
          "Application $applicationId has status ${existing.status}; ignoring submission request")
      ApplicationSubmissionResult(existing, emptyList())
    }
  }

  private fun updateStatus(applicationId: ApplicationId, status: ApplicationStatus) {
    with(APPLICATIONS) {
      dslContext
          .update(APPLICATIONS)
          .set(APPLICATION_STATUS_ID, status)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .where(ID.eq(applicationId))
          .execute()
    }

    insertHistory(applicationId)
  }

  /**
   * Updates an application's project boundary. If the application doesn't yet have an internal
   * name, and the boundary is within a single country, sets the internal name based on the country
   * and organization name.
   */
  fun updateBoundary(applicationId: ApplicationId, boundary: Geometry) {
    requirePermissions { updateApplicationBoundary(applicationId) }

    dslContext.transaction { _ ->
      val existing = fetchOneById(applicationId)

      if (existing.internalName == null) {
        val countries = countryDetector.getCountries(boundary)

        if (countries.size == 1) {
          val alpha3CountryCode =
              countriesDao.fetchOneByCode(countries.single())?.codeAlpha3 ?: "XXX"
          val organizationName =
              organizationsDao.fetchOneById(existing.organizationId)?.name
                  ?: throw OrganizationNotFoundException(existing.organizationId)
          val internalName = "${alpha3CountryCode}_$organizationName"

          updateInternalName(applicationId, internalName)
        } else {
          log.debug(
              "Not setting internal name for application $applicationId because boundary is not " +
                  "all in one country: $countries")
        }
      }

      with(APPLICATIONS) {
        dslContext
            .update(APPLICATIONS)
            .set(BOUNDARY, boundary)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .where(ID.eq(applicationId))
            .execute()
      }

      insertHistory(applicationId)
    }
  }

  /**
   * Updates the internal name of an application, attempting to make the name unique if it is
   * already in use.
   *
   * The uniqueness calculation is not robust against concurrent attempts to use the same name.
   * Given the low volume of expected usage, it isn't worth the added complexity to make it
   * bulletproof.
   *
   * @return The name that was actually used, possibly including a suffix.
   */
  private fun updateInternalName(applicationId: ApplicationId, internalName: String): String {
    return with(APPLICATIONS) {
      // If the internal name already exists, add the first unused numeric suffix to make it unique.
      val existingNames =
          dslContext
              .select(INTERNAL_NAME)
              .from(APPLICATIONS)
              .where(INTERNAL_NAME.startsWith(internalName))
              .fetchSet(INTERNAL_NAME)

      val suffixedName =
          if (internalName !in existingNames) {
            internalName
          } else {
            (2..(existingNames.size + 1))
                .asSequence()
                .map { "${internalName}_$it" }
                .first { it !in existingNames }
          }

      dslContext
          .update(APPLICATIONS)
          .set(INTERNAL_NAME, suffixedName)
          .where(ID.eq(applicationId))
          .execute()

      suffixedName
    }
  }

  private fun insertHistory(applicationId: ApplicationId) {
    dslContext
        .insertInto(
            APPLICATION_HISTORIES,
            APPLICATION_HISTORIES.APPLICATION_ID,
            APPLICATION_HISTORIES.APPLICATION_STATUS_ID,
            APPLICATION_HISTORIES.BOUNDARY,
            APPLICATION_HISTORIES.FEEDBACK,
            APPLICATION_HISTORIES.INTERNAL_COMMENT,
            APPLICATION_HISTORIES.MODIFIED_BY,
            APPLICATION_HISTORIES.MODIFIED_TIME)
        .select(
            DSL.select(
                    APPLICATIONS.ID,
                    APPLICATIONS.APPLICATION_STATUS_ID,
                    APPLICATIONS.BOUNDARY,
                    APPLICATIONS.FEEDBACK,
                    APPLICATIONS.INTERNAL_COMMENT,
                    APPLICATIONS.MODIFIED_BY,
                    APPLICATIONS.MODIFIED_TIME)
                .from(APPLICATIONS)
                .where(APPLICATIONS.ID.eq(applicationId)))
        .execute()
  }

  private fun fetchByCondition(condition: Condition): List<ExistingApplicationModel> {
    val conditionWithPermission =
        if (currentUser().canReadAllAcceleratorDetails()) {
          condition
        } else {
          val adminOrgIds = currentUser().adminOrganizations()
          if (adminOrgIds.isNotEmpty()) {
            condition.and(APPLICATIONS.projects.ORGANIZATION_ID.`in`(adminOrgIds))
          } else {
            return emptyList()
          }
        }

    return dslContext
        .select(APPLICATIONS.asterisk(), APPLICATIONS.projects.ORGANIZATION_ID)
        .from(APPLICATIONS)
        .where(conditionWithPermission)
        .orderBy(APPLICATIONS.ID)
        .fetch { ExistingApplicationModel.of(it) }
  }

  private fun checkPreScreenCriteria(
      application: ExistingApplicationModel,
      preScreenVariableValues: PreScreenVariableValues
  ): List<String> {
    val problems = mutableListOf<String>()

    var countryCode: String? = null
    var siteAreaHa: Double? = null

    if (application.boundary != null) {
      siteAreaHa = application.boundary.calculateAreaHectares().toDouble()

      val countries = countryDetector.getCountries(application.boundary)
      when (countries.size) {
        0 -> problems.add(messages.applicationPreScreenFailureNoCountry())
        1 -> countryCode = countries.first()
        else -> problems.add(messages.applicationPreScreenFailureMultipleCountries())
      }
    } else {
      problems.add(messages.applicationPreScreenFailureNoBoundary())
    }

    val countriesRow = countryCode?.let { countriesDao.fetchOneByCode(it) }

    if (countriesRow != null && siteAreaHa != null) {
      if (countriesRow.eligible != true) {
        // TODO: Look up localized country name
        problems.add(messages.applicationPreScreenFailureIneligibleCountry(countriesRow.name!!))
      } else {
        val minimumHectares = perCountryMinimumHectares[countryCode] ?: defaultMinimumHectares

        if (siteAreaHa < minimumHectares || siteAreaHa > defaultMaximumHectares) {
          problems.add(
              messages.applicationPreScreenFailureBadSize(
                  countriesRow.name!!, minimumHectares, defaultMaximumHectares))
        }
      }
    }

    if (siteAreaHa != null) {
      val totalLandUseArea =
          preScreenVariableValues.landUseModelHectares.values.sumOf { it.toDouble() }
      val differenceFromSiteArea = abs(siteAreaHa - totalLandUseArea)

      if (differenceFromSiteArea > siteAreaHa * landUseTotalFuzzPercent / 100.0) {
        problems.add(
            messages.applicationPreScreenFailureLandUseTotalTooLow(
                siteAreaHa.toInt(), totalLandUseArea.toInt()))
      }

      val monocultureArea =
          preScreenVariableValues.landUseModelHectares[LandUseModelType.Monoculture]
      if (monocultureArea != null &&
          monocultureArea.toDouble() > totalLandUseArea * monocultureMaxPercent / 100.0) {
        problems.add(messages.applicationPreScreenFailureMonocultureTooHigh(monocultureMaxPercent))
      }
    }

    val minimumSpecies = minimumSpeciesByProjectType[preScreenVariableValues.projectType]
    if (minimumSpecies != null &&
        (preScreenVariableValues.numSpeciesToBePlanted ?: 0) < minimumSpecies) {
      problems.add(messages.applicationPreScreenFailureTooFewSpecies(minimumSpecies))
    }

    return problems
  }
}
