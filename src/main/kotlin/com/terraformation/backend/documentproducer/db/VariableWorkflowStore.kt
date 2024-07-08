package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.db.docprod.tables.references.VARIABLES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_WORKFLOW_HISTORY
import com.terraformation.backend.documentproducer.model.ExistingVariableWorkflowHistoryModel
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class VariableWorkflowStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  /**
   * Returns the current workflow information for the variables in a project, filtered by
   * deliverable ID if supplied. Internal comment is only populated if the current user has
   * permission to read it.
   */
  fun fetchCurrentForProject(
      deliverableId: DeliverableId?,
      projectId: ProjectId
  ): Map<VariableId, ExistingVariableWorkflowHistoryModel> {
    requirePermissions { readProject(projectId) }

    val conditions =
        listOfNotNull(
            deliverableId?.let { VARIABLES.DELIVERABLE_ID.eq(it) },
            VARIABLE_WORKFLOW_HISTORY.PROJECT_ID.eq(projectId))

    return fetchCurrentByCondition(conditions).associateBy { it.variableId }
  }

  fun update(
      projectId: ProjectId,
      variableId: VariableId,
      status: VariableWorkflowStatus,
      feedback: String?,
      internalComment: String?,
  ) {
    requirePermissions { updateInternalVariableWorkflowDetails(projectId) }

    if (!dslContext.fetchExists(PROJECTS, PROJECTS.ID.eq(projectId))) {
      throw ProjectNotFoundException(projectId)
    }

    if (!dslContext.fetchExists(VARIABLES, VARIABLES.ID.eq(variableId))) {
      throw VariableNotFoundException(variableId)
    }

    with(VARIABLE_WORKFLOW_HISTORY) {
      dslContext
          .insertInto(VARIABLE_WORKFLOW_HISTORY)
          .set(CREATED_BY, currentUser().userId)
          .set(CREATED_TIME, clock.instant())
          .set(FEEDBACK, feedback)
          .set(INTERNAL_COMMENT, internalComment)
          .set(
              MAX_VARIABLE_VALUE_ID,
              DSL.field(DSL.select(DSL.max(VARIABLE_VALUES.ID)).from(VARIABLE_VALUES)))
          .set(PROJECT_ID, projectId)
          .set(VARIABLE_ID, variableId)
          .set(VARIABLE_WORKFLOW_STATUS_ID, status)
          .execute()
    }
  }

  private fun fetchCurrentByCondition(
      conditions: List<Condition>
  ): List<ExistingVariableWorkflowHistoryModel> {
    return with(VARIABLE_WORKFLOW_HISTORY) {
      dslContext
          .select(
              CREATED_BY,
              CREATED_TIME,
              FEEDBACK,
              ID,
              INTERNAL_COMMENT,
              MAX_VARIABLE_VALUE_ID,
              PROJECT_ID,
              VARIABLE_ID,
              VARIABLE_WORKFLOW_STATUS_ID,
          )
          .distinctOn(PROJECT_ID, VARIABLE_ID)
          .from(VARIABLE_WORKFLOW_HISTORY)
          .join(VARIABLES)
          .on(VARIABLES.ID.eq(VARIABLE_ID))
          .where(conditions)
          .orderBy(PROJECT_ID, VARIABLE_ID, ID.desc())
          .fetch { record ->
            val model = ExistingVariableWorkflowHistoryModel(record)

            if (currentUser().canReadInternalVariableWorkflowDetails(model.projectId)) {
              model
            } else {
              model.copy(internalComment = null)
            }
          }
    }
  }
}
