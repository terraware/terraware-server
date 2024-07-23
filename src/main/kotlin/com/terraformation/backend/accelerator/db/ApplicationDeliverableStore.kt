package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_DOCUMENTS
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import javax.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class ApplicationDeliverableStore(
  private val dslContext: DSLContext,
) {
  fun fetch(
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
            )
        )

    return dslContext
        .select(
            DELIVERABLE_DOCUMENTS.TEMPLATE_URL,
            DELIVERABLES.DELIVERABLE_CATEGORY_ID,
            DELIVERABLES.DELIVERABLE_TYPE_ID,
            DELIVERABLES.DESCRIPTION_HTML,
            DELIVERABLES.ID,
            DELIVERABLES.MODULE_ID,
            MODULES.NAME,
            DELIVERABLES.NAME,
            ORGANIZATIONS.ID,
            ORGANIZATIONS.NAME,
            PROJECTS.ID,
            PROJECTS.NAME,
            SUBMISSIONS.FEEDBACK,
            SUBMISSIONS.ID,
            SUBMISSIONS.INTERNAL_COMMENT,
            SUBMISSIONS.SUBMISSION_STATUS_ID,
        )
        .from(DELIVERABLES)
        .join(MODULES)
        .on(DELIVERABLES.MODULE_ID.eq(MODULES.ID))
        .leftJoin(SUBMISSIONS)
        .on(DELIVERABLES.ID.eq(SUBMISSIONS.DELIVERABLE_ID))
        .join(PROJECTS)
        .on(SUBMISSIONS.PROJECT_ID.eq(PROJECTS.ID))
        .leftJoin(APPLICATIONS)
        .on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
        .join(ORGANIZATIONS)
        .on(PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
        .where(conditions)
        .orderBy(DELIVERABLES.ID, PROJECTS.ID)
        .fetch { record ->
          DeliverableSubmissionModel(
              category = record[DELIVERABLES.DELIVERABLE_CATEGORY_ID]!!,
              deliverableId = record[DELIVERABLES.ID]!!,
              descriptionHtml = record[DELIVERABLES.DESCRIPTION_HTML],
              documents = emptyList(),
              dueDate = null,
              feedback = record[SUBMISSIONS.FEEDBACK],
              internalComment = record[SUBMISSIONS.INTERNAL_COMMENT],
              moduleId = record[DELIVERABLES.MODULE_ID]!!,
              moduleName = record[MODULES.NAME]!!,
              moduleTitle = record[COHORT_MODULES.TITLE]!!,
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
}
