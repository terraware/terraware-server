package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.VariableWorkflowHistoryId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_WORKFLOW_HISTORY
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class ExistingVariableWorkflowHistoryModel(
    val createdBy: UserId,
    val createdTime: Instant,
    val feedback: String?,
    val id: VariableWorkflowHistoryId,
    val internalComment: String?,
    val maxVariableValueId: VariableValueId,
    /** The ID of the variable at the time the workflow event happened. */
    val originalVariableId: VariableId,
    val projectId: ProjectId,
    val status: VariableWorkflowStatus,
    /**
     * The current ID of the variable. May differ from [originalVariableId] if the variable was
     * replaced after the workflow event happened; in that case this will be the most recent
     * variable in the chain of replacements of the original one.
     */
    val variableId: VariableId,
) {
  companion object {
    fun of(
        record: Record,
        currentVariableIdField: Field<VariableId?> = VARIABLE_WORKFLOW_HISTORY.VARIABLE_ID,
    ): ExistingVariableWorkflowHistoryModel {
      val projectId = record[VARIABLE_WORKFLOW_HISTORY.PROJECT_ID]!!
      val internalComment =
          if (currentUser().canReadInternalVariableWorkflowDetails(projectId)) {
            record[VARIABLE_WORKFLOW_HISTORY.INTERNAL_COMMENT]
          } else {
            null
          }

      return ExistingVariableWorkflowHistoryModel(
          createdBy = record[VARIABLE_WORKFLOW_HISTORY.CREATED_BY]!!,
          createdTime = record[VARIABLE_WORKFLOW_HISTORY.CREATED_TIME]!!,
          feedback = record[VARIABLE_WORKFLOW_HISTORY.FEEDBACK],
          id = record[VARIABLE_WORKFLOW_HISTORY.ID]!!,
          internalComment = internalComment,
          maxVariableValueId = record[VARIABLE_WORKFLOW_HISTORY.MAX_VARIABLE_VALUE_ID]!!,
          originalVariableId = record[VARIABLE_WORKFLOW_HISTORY.VARIABLE_ID]!!,
          projectId = projectId,
          status = record[VARIABLE_WORKFLOW_HISTORY.VARIABLE_WORKFLOW_STATUS_ID]!!,
          variableId = record[currentVariableIdField]!!,
      )
    }
  }
}
