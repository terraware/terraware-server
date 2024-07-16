package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.accelerator.tables.references.APPLICATION_HISTORIES
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.InstantSource
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
    private val organizationsDao: OrganizationsDao,
) {
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

  fun submit(applicationId: ApplicationId) {
    requirePermissions { updateApplicationSubmissionStatus(applicationId) }

    val existing = fetchOneById(applicationId)

    if (existing.status == ApplicationStatus.NotSubmitted) {
      updateStatus(applicationId, ApplicationStatus.Submitted)
    } else {
      log.info(
          "Application $applicationId has status ${existing.status}; ignoring submission request")
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
    return dslContext
        .select(APPLICATIONS.asterisk(), APPLICATIONS.projects.ORGANIZATION_ID)
        .from(APPLICATIONS)
        .where(condition)
        .orderBy(APPLICATIONS.ID)
        .fetch { ExistingApplicationModel.of(it) }
  }
}
