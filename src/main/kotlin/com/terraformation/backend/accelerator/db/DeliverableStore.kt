package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.accelerator.model.ModuleDeliverableModel
import com.terraformation.backend.accelerator.model.SubmissionDocumentModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_DOCUMENTS
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_PROJECT_DUE_DATES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSION_DOCUMENTS
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class DeliverableStore(
    private val dslContext: DSLContext,
) {
  fun deliverableIdExists(id: DeliverableId): Boolean {
    requirePermissions { readAllDeliverables() }

    return dslContext.fetchExists(DELIVERABLES, DELIVERABLES.ID.eq(id))
  }

  fun fetchDeliverableCategory(deliverableId: DeliverableId): DeliverableCategory {
    return dslContext
        .select(DELIVERABLES.DELIVERABLE_CATEGORY_ID)
        .from(DELIVERABLES)
        .where(DELIVERABLES.ID.eq(deliverableId))
        .fetchOne(DELIVERABLES.DELIVERABLE_CATEGORY_ID)
        ?: throw DeliverableNotFoundException(deliverableId)
  }

  fun fetchDeliverableModuleId(deliverableId: DeliverableId): ModuleId {
    return dslContext
        .select(DELIVERABLES.MODULE_ID)
        .from(DELIVERABLES)
        .where(DELIVERABLES.ID.eq(deliverableId))
        .fetchOne(DELIVERABLES.MODULE_ID) ?: throw DeliverableNotFoundException(deliverableId)
  }

  fun fetchDeliverables(
      deliverableId: DeliverableId? = null,
      moduleId: ModuleId? = null,
  ): List<ModuleDeliverableModel> {
    requirePermissions {
      when {
        moduleId != null -> readModule(moduleId)
        else -> readAllDeliverables()
      }
    }

    val conditions =
        listOfNotNull(
            deliverableId?.let { DELIVERABLES.ID.eq(it) },
            moduleId?.let { DELIVERABLES.MODULE_ID.eq(it) },
        )

    return dslContext.selectFrom(DELIVERABLES).where(conditions).fetch {
      ModuleDeliverableModel.of(it)
    }
  }

  fun fetchDeliverableSubmissions(
      organizationId: OrganizationId? = null,
      projectId: ProjectId? = null,
      deliverableId: DeliverableId? = null,
      moduleId: ModuleId? = null,
  ): List<DeliverableSubmissionModel> {
    requirePermissions {
      when {
        projectId != null -> readProjectDeliverables(projectId)
        organizationId != null -> readOrganizationDeliverables(organizationId)
        moduleId != null -> readModule(moduleId)
        else -> readAllDeliverables()
      }
    }

    val conditions =
        listOfNotNull(
            when {
              projectId != null -> PROJECTS.ID.eq(projectId)
              organizationId != null -> ORGANIZATIONS.ID.eq(organizationId)
              else -> null
            },
            deliverableId?.let { DELIVERABLES.ID.eq(it) },
            moduleId?.let { DELIVERABLES.MODULE_ID.eq(it) },
        )

    val documentsMultiset =
        DSL.multiset(
                DSL.select(SUBMISSION_DOCUMENTS.asterisk())
                    .from(SUBMISSION_DOCUMENTS)
                    .where(SUBMISSION_DOCUMENTS.SUBMISSION_ID.eq(SUBMISSIONS.ID))
                    .orderBy(SUBMISSION_DOCUMENTS.ID)
            )
            .convertFrom { result -> result.map { SubmissionDocumentModel.of(it) } }

    val dueDateField = DSL.coalesce(DELIVERABLE_PROJECT_DUE_DATES.DUE_DATE, COHORT_MODULES.END_DATE)

    val deliverableIdField = DELIVERABLES.ID.`as`("deliverable_id")
    val projectIdField = PROJECTS.ID.`as`("project_id")

    val nonCohortConditions =
        DSL.and(
            listOfNotNull(
                COHORT_MODULES.COHORT_ID.isNull,
                // Exclude pre-screen and application deliverables unless the caller is asking for
                // specific deliverables.
                if (deliverableId == null) {
                  MODULES.PHASE_ID.notIn(CohortPhase.PreScreen, CohortPhase.Application)
                } else {
                  null
                },
            )
        )

    // All the fields we query, minus the due date since it varies depending on whether or not we're
    // querying participant project submissions.
    val fieldList =
        arrayOf(
            DELIVERABLE_DOCUMENTS.TEMPLATE_URL,
            DELIVERABLES.DELIVERABLE_CATEGORY_ID,
            DELIVERABLES.DELIVERABLE_TYPE_ID,
            DELIVERABLES.DESCRIPTION_HTML,
            deliverableIdField,
            DELIVERABLES.IS_REQUIRED,
            DELIVERABLES.IS_SENSITIVE,
            DELIVERABLES.MODULE_ID,
            MODULES.NAME,
            COHORTS.ID,
            COHORTS.NAME,
            COHORT_MODULES.TITLE,
            DELIVERABLES.NAME,
            DELIVERABLES.POSITION,
            documentsMultiset,
            ORGANIZATIONS.ID,
            ORGANIZATIONS.NAME,
            projectIdField,
            PROJECTS.NAME,
            PROJECT_ACCELERATOR_DETAILS.DEAL_NAME,
            SUBMISSIONS.FEEDBACK,
            SUBMISSIONS.ID,
            SUBMISSIONS.INTERNAL_COMMENT,
            SUBMISSIONS.MODIFIED_TIME,
            SUBMISSIONS.SUBMISSION_STATUS_ID,
        )

    val query =
        if (projectId != null && deliverableId != null) {
          // Project and deliverable both specified, so return a single result (possibly an empty
          // submission) regardless of whether or not there's a cohort and regardless of which
          // cohort phase the deliverable's module is in.
          dslContext
              .select(*fieldList, dueDateField)
              .from(DELIVERABLES)
              .join(MODULES)
              .on(DELIVERABLES.MODULE_ID.eq(MODULES.ID))
              .join(PROJECTS)
              .on(PROJECTS.ID.eq(projectId))
              .leftJoin(PROJECT_ACCELERATOR_DETAILS)
              .on(PROJECTS.ID.eq(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID))
              .join(ORGANIZATIONS)
              .on(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
              .leftJoin(COHORTS)
              .on(PROJECTS.COHORT_ID.eq(COHORTS.ID))
              .leftJoin(COHORT_MODULES)
              .on(MODULES.ID.eq(COHORT_MODULES.MODULE_ID))
              .and(COHORTS.ID.eq(COHORT_MODULES.COHORT_ID))
              .leftJoin(DELIVERABLE_DOCUMENTS)
              .on(DELIVERABLES.ID.eq(DELIVERABLE_DOCUMENTS.DELIVERABLE_ID))
              .leftJoin(SUBMISSIONS)
              .on(DELIVERABLES.ID.eq(SUBMISSIONS.DELIVERABLE_ID))
              .and(PROJECTS.ID.eq(SUBMISSIONS.PROJECT_ID))
              .leftJoin(DELIVERABLE_PROJECT_DUE_DATES)
              .on(DELIVERABLE_PROJECT_DUE_DATES.DELIVERABLE_ID.eq(DELIVERABLES.ID))
              .and(DELIVERABLE_PROJECT_DUE_DATES.PROJECT_ID.eq(PROJECTS.ID))
              .where(conditions)
        } else {
          // Return submission data (possibly empty submissions) for deliverables in modules that
          // the project's cohort is a member of.
          dslContext
              .select(*fieldList, dueDateField)
              .from(DELIVERABLES)
              .join(MODULES)
              .on(DELIVERABLES.MODULE_ID.eq(MODULES.ID))
              .join(COHORT_MODULES)
              .on(MODULES.ID.eq(COHORT_MODULES.MODULE_ID))
              .join(COHORTS)
              .on(COHORT_MODULES.COHORT_ID.eq(COHORTS.ID))
              .join(PROJECTS)
              .on(COHORTS.ID.eq(PROJECTS.COHORT_ID))
              .leftJoin(PROJECT_ACCELERATOR_DETAILS)
              .on(PROJECTS.ID.eq(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID))
              .join(ORGANIZATIONS)
              .on(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
              .leftJoin(DELIVERABLE_DOCUMENTS)
              .on(DELIVERABLES.ID.eq(DELIVERABLE_DOCUMENTS.DELIVERABLE_ID))
              .leftJoin(SUBMISSIONS)
              .on(DELIVERABLES.ID.eq(SUBMISSIONS.DELIVERABLE_ID))
              .and(PROJECTS.ID.eq(SUBMISSIONS.PROJECT_ID))
              .leftJoin(DELIVERABLE_PROJECT_DUE_DATES)
              .on(DELIVERABLE_PROJECT_DUE_DATES.DELIVERABLE_ID.eq(DELIVERABLES.ID))
              .and(DELIVERABLE_PROJECT_DUE_DATES.PROJECT_ID.eq(PROJECTS.ID))
              .where(conditions)
              .union(
                  // There are "submissions" from importing project data from the old project data
                  // hub. The projects don't necessarily have cohorts but they can have submissions
                  // for deliverables that would ordinarily only appear for projects in cohorts.
                  // Return these submissions if they exist, even if there's no cohort in the
                  // picture.
                  DSL.select(*fieldList, DELIVERABLE_PROJECT_DUE_DATES.DUE_DATE.`as`(dueDateField))
                      .from(SUBMISSIONS)
                      .join(DELIVERABLES)
                      .on(SUBMISSIONS.DELIVERABLE_ID.eq(DELIVERABLES.ID))
                      .join(MODULES)
                      .on(DELIVERABLES.MODULE_ID.eq(MODULES.ID))
                      .join(PROJECTS)
                      .on(SUBMISSIONS.PROJECT_ID.eq(PROJECTS.ID))
                      .leftJoin(PROJECT_ACCELERATOR_DETAILS)
                      .on(PROJECTS.ID.eq(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID))
                      .join(ORGANIZATIONS)
                      .on(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                      .leftJoin(COHORTS)
                      .on(PROJECTS.COHORT_ID.eq(COHORTS.ID))
                      .leftJoin(COHORT_MODULES)
                      .on(COHORTS.ID.eq(COHORT_MODULES.COHORT_ID))
                      .and(MODULES.ID.eq(COHORT_MODULES.MODULE_ID))
                      .leftJoin(DELIVERABLE_PROJECT_DUE_DATES)
                      .on(DELIVERABLES.ID.eq(DELIVERABLE_PROJECT_DUE_DATES.DELIVERABLE_ID))
                      .and(PROJECTS.ID.eq(DELIVERABLE_PROJECT_DUE_DATES.PROJECT_ID))
                      .leftJoin(DELIVERABLE_DOCUMENTS)
                      .on(DELIVERABLES.ID.eq(DELIVERABLE_DOCUMENTS.DELIVERABLE_ID))
                      .and(DELIVERABLES.ID.eq(DELIVERABLE_PROJECT_DUE_DATES.DELIVERABLE_ID))
                      .where(conditions)
                      .and(nonCohortConditions)
              )
              .orderBy(deliverableIdField, projectIdField)
        }

    return query.fetch { record ->
      DeliverableSubmissionModel(
          category = record[DELIVERABLES.DELIVERABLE_CATEGORY_ID]!!,
          cohortId = record[COHORTS.ID],
          cohortName = record[COHORTS.NAME],
          deliverableId = record[deliverableIdField]!!,
          descriptionHtml = record[DELIVERABLES.DESCRIPTION_HTML],
          documents = record[documentsMultiset],
          dueDate = record[dueDateField],
          feedback = record[SUBMISSIONS.FEEDBACK],
          internalComment = record[SUBMISSIONS.INTERNAL_COMMENT],
          modifiedTime = record[SUBMISSIONS.MODIFIED_TIME],
          moduleId = record[DELIVERABLES.MODULE_ID]!!,
          moduleName = record[MODULES.NAME]!!,
          moduleTitle = record[COHORT_MODULES.TITLE],
          name = record[DELIVERABLES.NAME]!!,
          organizationId = record[ORGANIZATIONS.ID]!!,
          organizationName = record[ORGANIZATIONS.NAME]!!,
          position = record[DELIVERABLES.POSITION]!!,
          projectDealName = record[PROJECT_ACCELERATOR_DETAILS.DEAL_NAME],
          projectId = record[projectIdField]!!,
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
}
