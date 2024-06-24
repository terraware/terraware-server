package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_WORKFLOW_HISTORY
import com.terraformation.backend.documentproducer.model.ExistingVariableWorkflowHistoryModel
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class VariableWorkflowStore(
    private val dslContext: DSLContext,
) {
  /**
   * Returns the current workflow information for the variables in a project. Internal comment is
   * only populated if the current user has permission to read it.
   */
  fun fetchCurrentForProject(
      projectId: ProjectId
  ): Map<VariableId, ExistingVariableWorkflowHistoryModel> {
    requirePermissions { readProject(projectId) }

    return fetchCurrentByCondition(VARIABLE_WORKFLOW_HISTORY.PROJECT_ID.eq(projectId)).associateBy {
      it.variableId
    }
  }

  private fun fetchCurrentByCondition(
      condition: Condition
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
          .where(condition)
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
