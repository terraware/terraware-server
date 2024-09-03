package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.ApplicationSubmittedEvent
import com.terraformation.backend.accelerator.model.ApplicationModuleModel
import com.terraformation.backend.accelerator.model.ApplicationSubmissionResult
import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.accelerator.model.PreScreenVariableValues
import com.terraformation.backend.accelerator.model.SubmissionDocumentModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNotFoundException
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
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.calculateAreaHectares
import jakarta.inject.Named
import java.time.InstantSource
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
            DELIVERABLE_DOCUMENTS.TEMPLATE_URL,
            DELIVERABLES.DELIVERABLE_CATEGORY_ID,
            DELIVERABLES.DELIVERABLE_TYPE_ID,
            DELIVERABLES.DESCRIPTION_HTML,
            DELIVERABLES.ID,
            DELIVERABLES.MODULE_ID,
            DELIVERABLES.NAME,
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
              projectId = record[PROJECTS.ID]!!,
              projectName = record[PROJECTS.NAME]!!,
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
    val organizationName =
        dslContext
            .select(PROJECTS.organizations.NAME)
            .from(PROJECTS)
            .where(PROJECTS.ID.eq(projectId))
            .fetchOne(PROJECTS.organizations.NAME) ?: throw ProjectNotFoundException(projectId)

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

      updateInternalName(applicationId, "${defaultInternalNamePrefix}_$organizationName")

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
      updatePrescreenFeedback(applicationId, problems)

      if (problems.isNotEmpty()) {
        updateStatus(applicationId, ApplicationStatus.FailedPreScreen)
      } else {
        updateStatus(applicationId, ApplicationStatus.PassedPreScreen)
        assignModules(applicationId, CohortPhase.Application)
      }

      ApplicationSubmissionResult(fetchOneById(applicationId), problems)
    } else if (existing.status == ApplicationStatus.PassedPreScreen) {
      val modules = fetchModulesByApplicationId(existing.id, CohortPhase.Application)
      if (modules.all { it.applicationModuleStatus == ApplicationModuleStatus.Complete }) {
        updateStatus(applicationId, ApplicationStatus.Submitted)
        eventPublisher.publishEvent(ApplicationSubmittedEvent(applicationId, clock.instant()))
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
      val existing = fetchOneById(applicationId)

      if (existing.internalName.startsWith(defaultInternalNamePrefix)) {
        val countries = countryDetector.getCountries(boundary)

        if (countries.size == 1) {
          val countryCode = countries.single()
          val alpha3CountryCode = countriesDao.fetchOneByCode(countryCode)?.codeAlpha3 ?: "XXX"
          val organizationName =
              organizationsDao.fetchOneById(existing.organizationId)?.name
                  ?: throw OrganizationNotFoundException(existing.organizationId)
          val internalName = "${alpha3CountryCode}_$organizationName"

          updateInternalName(applicationId, internalName)
          updateCountryCode(applicationId, countryCode)
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

  /** Updates the country code of an application. */
  private fun updateCountryCode(applicationId: ApplicationId, countryCode: String) {
    with(APPLICATIONS) {
      dslContext
          .update(APPLICATIONS)
          .set(COUNTRY_CODE, countryCode)
          .where(ID.eq(applicationId))
          .execute()
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
      preScreenVariableValues: PreScreenVariableValues
  ): List<String> {
    val problems = mutableListOf<String>()

    var countryCode: String? = null
    var siteAreaHa: Double? = null

    val totalLandUseArea =
        preScreenVariableValues.landUseModelHectares.values.sumOf { it.toDouble() }

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

        if (siteAreaHa < minimumHectares ||
            siteAreaHa > defaultMaximumHectares ||
            totalLandUseArea < minimumHectares ||
            totalLandUseArea > defaultMaximumHectares) {
          problems.add(
              messages.applicationPreScreenFailureBadSize(
                  countriesRow.name!!, minimumHectares, defaultMaximumHectares))
        }
      }
    }

    if (siteAreaHa != null) {
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
