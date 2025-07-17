package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.accelerator.event.ApplicationSubmittedEvent
import com.terraformation.backend.accelerator.model.ApplicationModuleModel
import com.terraformation.backend.accelerator.model.ApplicationSubmissionResult
import com.terraformation.backend.accelerator.model.ApplicationVariableValues
import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.accelerator.model.SubmissionDocumentModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationModuleStatus
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.records.ApplicationHistoriesRecord
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.accelerator.tables.references.APPLICATION_HISTORIES
import com.terraformation.backend.db.accelerator.tables.references.APPLICATION_MODULES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_DOCUMENTS
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_DOCUMENTS
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.InstantSource
import org.geotools.api.feature.simple.SimpleFeature
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.locationtech.jts.geom.Geometry
import org.springframework.context.ApplicationEventPublisher
import org.springframework.web.util.HtmlUtils

@Named
class ApplicationStore(
    private val clock: InstantSource,
    private val countriesDao: CountriesDao,
    private val countryDetector: CountryDetector,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val messages: Messages,
) {
  private val defaultMinimumHectares = 15000
  private val defaultMaximumHectares = 100000

  private val perCountryMinimumTotalHectares =
      mapOf(
          "GH" to 3000,
          "PH" to 3000,
      )

  private val perCountryMinimumMagroveHectares =
      mapOf(
          "ID" to 1000,
          "PH" to 1000,
      )

  /** Internal name country prefix used when country can't be determined from boundary. */
  private val defaultInternalNamePrefix = "XXX"

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

  fun fetchGeoFeatureById(applicationId: ApplicationId): SimpleFeature {
    requirePermissions { reviewApplication(applicationId) }

    return fetchByCondition(APPLICATIONS.ID.eq(applicationId)).firstOrNull()?.toGeoFeature()
        ?: throw ApplicationNotFoundException(applicationId)
  }

  fun fetchOneByInternalName(internalName: String): ExistingApplicationModel? {
    val application = fetchByCondition(APPLICATIONS.INTERNAL_NAME.eq(internalName)).singleOrNull()

    if (application != null) {
      requirePermissions { readApplication(application.id) }
    }

    return application
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

  fun fetchHistoryByApplicationId(applicationId: ApplicationId): List<ApplicationHistoriesRecord> {
    requirePermissions { readApplication(applicationId) }

    return with(APPLICATION_HISTORIES) {
      dslContext
          .selectFrom(APPLICATION_HISTORIES)
          .where(APPLICATION_ID.eq(applicationId))
          .orderBy(MODIFIED_TIME.desc())
          .fetch()
    }
  }

  fun fetchModulesByApplicationId(
      applicationId: ApplicationId,
      phase: CohortPhase? = null,
  ): List<ApplicationModuleModel> {
    requirePermissions { readApplication(applicationId) }

    val phaseCondition =
        when (phase) {
          CohortPhase.PreScreen -> MODULES.PHASE_ID.eq(CohortPhase.PreScreen)
          CohortPhase.Application -> MODULES.PHASE_ID.eq(CohortPhase.Application)
          else ->
              DSL.or(
                  MODULES.PHASE_ID.eq(CohortPhase.PreScreen),
                  MODULES.PHASE_ID.eq(CohortPhase.Application))
        }

    return with(MODULES) {
      dslContext
          .select(
              asterisk(),
              APPLICATION_MODULES.APPLICATION_ID,
              APPLICATION_MODULES.APPLICATION_MODULE_STATUS_ID)
          .from(this)
          .join(APPLICATION_MODULES)
          .on(APPLICATION_MODULES.MODULE_ID.eq(ID))
          .where(phaseCondition)
          .and(APPLICATION_MODULES.APPLICATION_ID.eq(applicationId))
          .orderBy(MODULES.PHASE_ID, MODULES.POSITION)
          .fetch { ApplicationModuleModel.of(it) }
    }
  }

  fun fetchApplicationDeliverables(
      organizationId: OrganizationId? = null,
      projectId: ProjectId? = null,
      applicationId: ApplicationId? = null,
      deliverableId: DeliverableId? = null,
      moduleId: ModuleId? = null,
  ): List<DeliverableSubmissionModel> {
    requirePermissions {
      when {
        projectId != null -> readProjectDeliverables(projectId)
        applicationId != null -> readApplication(applicationId)
        organizationId != null -> readOrganizationDeliverables(organizationId)
        moduleId != null -> readModule(moduleId)
        else -> readAllDeliverables()
      }
    }

    val conditions =
        listOfNotNull(
            when {
              projectId != null -> PROJECTS.ID.eq(projectId)
              applicationId != null -> APPLICATIONS.ID.eq(applicationId)
              organizationId != null -> ORGANIZATIONS.ID.eq(organizationId)
              else -> null
            },
            deliverableId?.let { DELIVERABLES.ID.eq(it) },
            moduleId?.let { DELIVERABLES.MODULE_ID.eq(it) },
            DSL.or(
                MODULES.PHASE_ID.eq(CohortPhase.PreScreen),
                MODULES.PHASE_ID.eq(CohortPhase.Application),
            ))

    val documentsMultiset =
        DSL.multiset(
                DSL.select(SUBMISSION_DOCUMENTS.asterisk())
                    .from(SUBMISSION_DOCUMENTS)
                    .where(SUBMISSION_DOCUMENTS.SUBMISSION_ID.eq(SUBMISSIONS.ID))
                    .orderBy(SUBMISSION_DOCUMENTS.ID))
            .convertFrom { result -> result.map { SubmissionDocumentModel.of(it) } }

    return dslContext
        .select(
            APPLICATIONS.INTERNAL_NAME,
            DELIVERABLE_DOCUMENTS.TEMPLATE_URL,
            DELIVERABLES.DELIVERABLE_CATEGORY_ID,
            DELIVERABLES.DELIVERABLE_TYPE_ID,
            DELIVERABLES.DESCRIPTION_HTML,
            DELIVERABLES.ID,
            DELIVERABLES.IS_REQUIRED,
            DELIVERABLES.IS_SENSITIVE,
            DELIVERABLES.MODULE_ID,
            DELIVERABLES.NAME,
            DELIVERABLES.POSITION,
            documentsMultiset,
            MODULES.NAME,
            ORGANIZATIONS.ID,
            ORGANIZATIONS.NAME,
            PROJECTS.ID,
            PROJECTS.NAME,
            SUBMISSIONS.FEEDBACK,
            SUBMISSIONS.ID,
            SUBMISSIONS.INTERNAL_COMMENT,
            SUBMISSIONS.MODIFIED_TIME,
            SUBMISSIONS.SUBMISSION_STATUS_ID,
        )
        .from(DELIVERABLES)
        .join(MODULES)
        .on(DELIVERABLES.MODULE_ID.eq(MODULES.ID))
        .join(APPLICATION_MODULES)
        .on(MODULES.ID.eq(APPLICATION_MODULES.MODULE_ID))
        .join(APPLICATIONS)
        .on(APPLICATION_MODULES.APPLICATION_ID.eq(APPLICATIONS.ID))
        .join(PROJECTS)
        .on(APPLICATIONS.PROJECT_ID.eq(PROJECTS.ID))
        .join(ORGANIZATIONS)
        .on(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
        .leftJoin(SUBMISSIONS)
        .on(DELIVERABLES.ID.eq(SUBMISSIONS.DELIVERABLE_ID))
        .and(SUBMISSIONS.PROJECT_ID.eq(PROJECTS.ID))
        .leftJoin(DELIVERABLE_DOCUMENTS)
        .on(DELIVERABLES.ID.eq(DELIVERABLE_DOCUMENTS.DELIVERABLE_ID))
        .where(conditions)
        .orderBy(DELIVERABLES.ID, PROJECTS.ID)
        .fetch { record ->
          DeliverableSubmissionModel(
              category = record[DELIVERABLES.DELIVERABLE_CATEGORY_ID]!!,
              cohortId = null,
              cohortName = null,
              deliverableId = record[DELIVERABLES.ID]!!,
              descriptionHtml = record[DELIVERABLES.DESCRIPTION_HTML],
              documents = record[documentsMultiset] ?: emptyList(),
              dueDate = null,
              feedback = record[SUBMISSIONS.FEEDBACK],
              internalComment = record[SUBMISSIONS.INTERNAL_COMMENT],
              modifiedTime = record[SUBMISSIONS.MODIFIED_TIME],
              moduleId = record[DELIVERABLES.MODULE_ID]!!,
              moduleName = record[MODULES.NAME]!!,
              moduleTitle = null,
              name = record[DELIVERABLES.NAME]!!,
              organizationId = record[ORGANIZATIONS.ID]!!,
              organizationName = record[ORGANIZATIONS.NAME]!!,
              participantId = null,
              participantName = null,
              position = record[DELIVERABLES.POSITION]!!,
              projectDealName = record[APPLICATIONS.INTERNAL_NAME],
              projectId = record[PROJECTS.ID]!!,
              projectName = record[PROJECTS.NAME]!!,
              required = record[DELIVERABLES.IS_REQUIRED]!!,
              sensitive = record[DELIVERABLES.IS_SENSITIVE]!!,
              status = record[SUBMISSIONS.SUBMISSION_STATUS_ID] ?: SubmissionStatus.NotSubmitted,
              submissionId = record[SUBMISSIONS.ID],
              templateUrl = record[DELIVERABLE_DOCUMENTS.TEMPLATE_URL],
              type = record[DELIVERABLES.DELIVERABLE_TYPE_ID]!!,
          )
        }
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
                .set(INTERNAL_NAME, defaultInternalNamePrefix)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .set(PROJECT_ID, projectId)
                .onConflict(PROJECT_ID)
                .doNothing()
                .returning(ID)
                .fetchOne(ID) ?: throw ProjectApplicationExistsException(projectId)
          }

      insertHistory(applicationId)

      assignModules(applicationId, CohortPhase.PreScreen)
      assignModules(applicationId, CohortPhase.Application)

      updateInternalName(applicationId)

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

    when (existing.status) {
      ApplicationStatus.NotSubmitted -> return // Do nothing here.
      ApplicationStatus.PassedPreScreen,
      ApplicationStatus.FailedPreScreen ->
          updateStatus(applicationId, ApplicationStatus.NotSubmitted)
      else -> throw IllegalStateException("Application in ${existing.status} cannot be restarted")
    }
  }

  /**
   * Submits an application. If it is being submitted for pre-screening, does the pre-screening
   * qualification checks.
   */
  fun submit(
      applicationId: ApplicationId,
      applicationVariableValues: ApplicationVariableValues? = null,
      boundarySubmission: DeliverableSubmissionModel? = null,
  ): ApplicationSubmissionResult {
    requirePermissions { updateApplicationSubmissionStatus(applicationId) }

    val existing = fetchOneById(applicationId)

    return if (existing.status == ApplicationStatus.NotSubmitted) {
      if (applicationVariableValues == null) {
        throw IllegalArgumentException(
            "No pre-screen variable values supplied for pre-screen submission")
      }

      val problems = checkPreScreenCriteria(existing, applicationVariableValues, boundarySubmission)
      updatePrescreenFeedback(applicationId, problems)

      if (problems.isNotEmpty()) {
        updateStatus(applicationId, ApplicationStatus.FailedPreScreen)
      } else {
        updateStatus(applicationId, ApplicationStatus.PassedPreScreen)
        assignModules(applicationId, CohortPhase.Application)

        applicationVariableValues.countryCode?.let { updateCountryCode(applicationId, it) }
      }

      ApplicationSubmissionResult(fetchOneById(applicationId), problems)
    } else if (existing.status == ApplicationStatus.PassedPreScreen) {
      val modules = fetchModulesByApplicationId(existing.id, CohortPhase.Application)
      if (modules.all { it.applicationModuleStatus == ApplicationModuleStatus.Complete }) {
        updateStatus(applicationId, ApplicationStatus.Submitted)
        eventPublisher.publishEvent(ApplicationSubmittedEvent(applicationId))
        ApplicationSubmissionResult(fetchOneById(applicationId), emptyList())
      } else {
        log.info("Application $applicationId has incomplete modules.")
        ApplicationSubmissionResult(existing, listOf(messages.applicationModulesIncomplete()))
      }
    } else {
      log.info(
          "Application $applicationId has status ${existing.status}; ignoring submission request")
      ApplicationSubmissionResult(existing, emptyList())
    }
  }

  fun updateModuleStatus(
      projectId: ProjectId,
      moduleId: ModuleId,
      status: ApplicationModuleStatus
  ) {
    val application =
        fetchByProjectId(projectId).firstOrNull()
            ?: throw ProjectApplicationNotFoundException(projectId)

    requirePermissions { updateApplicationSubmissionStatus(application.id) }

    val rowsUpdated =
        dslContext
            .update(APPLICATION_MODULES)
            .set(APPLICATION_MODULES.APPLICATION_MODULE_STATUS_ID, status)
            .where(APPLICATION_MODULES.APPLICATION_ID.eq(application.id))
            .and(APPLICATION_MODULES.MODULE_ID.eq(moduleId))
            .execute()

    if (rowsUpdated == 0) {
      throw ProjectModuleNotFoundException(projectId, moduleId)
    }
  }

  private fun updatePrescreenFeedback(applicationId: ApplicationId, feedback: List<String>) {
    val feedbackField =
        if (feedback.isNotEmpty()) {
          feedback.joinToString(
              prefix = "<ul>\n<li>", separator = "</li>\n<li>", postfix = "</li>\n</ul>") {
                HtmlUtils.htmlEscape(it)
              }
        } else {
          null
        }

    with(APPLICATIONS) {
      dslContext
          .update(APPLICATIONS)
          .set(FEEDBACK, feedbackField)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .where(ID.eq(applicationId))
          .execute()
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
   * Updates an application's project boundary. If the application doesn't yet have an internal name
   * or the internal name's prefix is [defaultInternalNamePrefix] rather than a country code, and
   * the boundary is within a single country, sets the internal name based on the country and
   * organization name.
   */
  fun updateBoundary(applicationId: ApplicationId, boundary: Geometry) {
    requirePermissions { updateApplicationBoundary(applicationId) }

    dslContext.transaction { _ ->
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
   * Updates the internal name of an application, using the application alpha3 country code and the
   * organization name, attempting to make the name unique if it is already in use.
   *
   * If the country column of an application is not set, "XXX" will be used as the country code.
   *
   * The uniqueness calculation is not robust against concurrent attempts to use the same name.
   * Given the low volume of expected usage, it isn't worth the added complexity to make it
   * bulletproof.
   *
   * @return The name that was actually used, possibly including a suffix.
   */
  private fun updateInternalName(applicationId: ApplicationId): String {
    return with(APPLICATIONS) {
      val application = fetchOneById(applicationId)

      val countryCode = application.countryCode
      val organizationName = application.organizationName

      val alpha3CountryCode =
          countryCode?.let { countriesDao.fetchOneByCode(it)?.codeAlpha3 } ?: "XXX"
      val internalName = "${alpha3CountryCode}_$organizationName"

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

      eventPublisher.publishEvent(ApplicationInternalNameUpdatedEvent(applicationId))

      suffixedName
    }
  }

  /** Updates the country code of an application. */
  fun updateCountryCode(applicationId: ApplicationId, countryCode: String) {
    requirePermissions { updateApplicationCountry(applicationId) }

    val rowsUpdated =
        with(APPLICATIONS) {
          dslContext
              .update(APPLICATIONS)
              .set(COUNTRY_CODE, countryCode)
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, clock.instant())
              .where(ID.eq(applicationId))
              .and(
                  DSL.or(
                      COUNTRY_CODE.notEqual(countryCode),
                      COUNTRY_CODE.isNull(),
                  ))
              .execute()
        }

    if (rowsUpdated > 0) {
      updateInternalName(applicationId)
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

  private fun assignModules(applicationId: ApplicationId, phase: CohortPhase) {
    if (phase == CohortPhase.PreScreen || phase == CohortPhase.Application) {
      with(APPLICATION_MODULES) {
        dslContext
            .insertInto(
                APPLICATION_MODULES, APPLICATION_ID, MODULE_ID, APPLICATION_MODULE_STATUS_ID)
            .select(
                DSL.select(
                        DSL.value(applicationId),
                        MODULES.ID,
                        DSL.value(ApplicationModuleStatus.Incomplete))
                    .from(MODULES)
                    .where(MODULES.PHASE_ID.eq(phase)))
            .onConflict()
            .doNothing()
            .execute()
      }
    }
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

    val modifiedTimeField =
        DSL.field(
            DSL.select(APPLICATION_HISTORIES.MODIFIED_TIME)
                .from(APPLICATION_HISTORIES)
                .where(APPLICATION_HISTORIES.APPLICATION_ID.eq(APPLICATIONS.ID))
                .orderBy(APPLICATION_HISTORIES.MODIFIED_TIME.desc())
                .limit(1))

    return dslContext
        .select(
            APPLICATIONS.asterisk(),
            APPLICATIONS.projects.ORGANIZATION_ID,
            APPLICATIONS.projects.NAME,
            APPLICATIONS.projects.organizations.NAME,
            modifiedTimeField,
        )
        .from(APPLICATIONS)
        .where(conditionWithPermission)
        .orderBy(APPLICATIONS.ID)
        .fetch { ExistingApplicationModel.of(it, modifiedTimeField) }
  }

  private fun checkPreScreenCriteria(
      application: ExistingApplicationModel,
      applicationVariableValues: ApplicationVariableValues,
      boundarySubmission: DeliverableSubmissionModel? = null,
  ): List<String> {
    val problems = mutableListOf<String>()

    var boundaryCountryCode: String? = null

    val totalLandUseArea =
        applicationVariableValues.landUseModelHectares.values.sumOf { it.toDouble() }

    if (application.boundary != null) {
      val countries = countryDetector.getCountries(application.boundary)
      when (countries.size) {
        0 -> problems.add(messages.applicationPreScreenBoundaryInNoCountry())
        1 -> boundaryCountryCode = countries.first()
        else -> problems.add(messages.applicationPreScreenFailureMultipleCountries())
      }
    } else if (boundarySubmission == null ||
        boundarySubmission.status != SubmissionStatus.Completed) {
      problems.add(messages.applicationPreScreenFailureNoBoundary())
    }

    if (problems.isNotEmpty()) {
      return problems
    }

    if (applicationVariableValues.countryCode == null) {
      problems.add(messages.applicationPreScreenFailureNoCountry())
    }

    // TODO: Look up localized country name
    val boundaryCountriesRow = boundaryCountryCode?.let { countriesDao.fetchOneByCode(it) }
    val projectCountriesRow =
        applicationVariableValues.countryCode?.let { countriesDao.fetchOneByCode(it) }

    if (projectCountriesRow != null) {
      if (projectCountriesRow.eligible != true) {
        // First, check if the application country is eligible
        problems.add(
            messages.applicationPreScreenFailureIneligibleCountry(projectCountriesRow.name!!))
      } else if (boundaryCountriesRow != null && boundaryCountriesRow != projectCountriesRow) {
        // Second, check if the provided boundary matches the country
        problems.add(
            messages.applicationPreScreenFailureMismatchCountries(
                boundaryCountriesRow.name!!, projectCountriesRow.name!!))
      }

      // Third, check minimum hectares requirements
      val minimumHectares =
          perCountryMinimumTotalHectares[projectCountriesRow.code!!] ?: defaultMinimumHectares
      val minimumMangroveHectares = perCountryMinimumMagroveHectares[projectCountriesRow.code!!]

      val mangroveLandUseArea =
          applicationVariableValues.landUseModelHectares[LandUseModelType.Mangroves]?.toDouble()
              ?: 0.0

      if (!(totalLandUseArea >= minimumHectares ||
          minimumMangroveHectares?.let { mangroveLandUseArea >= it } ?: false) ||
          totalLandUseArea > defaultMaximumHectares) {
        problems.add(
            messages.applicationPreScreenFailureBadSize(
                projectCountriesRow.name!!,
                minimumHectares,
                defaultMaximumHectares,
                minimumMangroveHectares))
      }
    }

    val minimumSpecies = minimumSpeciesByProjectType[applicationVariableValues.projectType]
    if (minimumSpecies != null &&
        (applicationVariableValues.numSpeciesToBePlanted ?: 0) < minimumSpecies) {
      problems.add(messages.applicationPreScreenFailureTooFewSpecies(minimumSpecies))
    }

    return problems
  }
}
