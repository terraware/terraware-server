package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.VariableWorkflowHistoryId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.db.docprod.tables.pojos.VariableWorkflowHistoryRow
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_WORKFLOW_HISTORY
import java.time.Instant
import org.jooq.Record

data class ExistingVariableWorkflowHistoryModel(
    val createdBy: UserId,
    val createdTime: Instant,
    val feedback: String?,
    val id: VariableWorkflowHistoryId,
    val internalComment: String?,
    val maxVariableValueId: VariableValueId,
    val projectId: ProjectId,
    val status: VariableWorkflowStatus,
    val variableId: VariableId,
) {
  constructor(
      record: Record
  ) : this(
      createdBy = record[VARIABLE_WORKFLOW_HISTORY.CREATED_BY]!!,
      createdTime = record[VARIABLE_WORKFLOW_HISTORY.CREATED_TIME]!!,
      feedback = record[VARIABLE_WORKFLOW_HISTORY.FEEDBACK],
      id = record[VARIABLE_WORKFLOW_HISTORY.ID]!!,
      internalComment = record[VARIABLE_WORKFLOW_HISTORY.INTERNAL_COMMENT],
      maxVariableValueId = record[VARIABLE_WORKFLOW_HISTORY.MAX_VARIABLE_VALUE_ID]!!,
      projectId = record[VARIABLE_WORKFLOW_HISTORY.PROJECT_ID]!!,
      status = record[VARIABLE_WORKFLOW_HISTORY.VARIABLE_WORKFLOW_STATUS_ID]!!,
      variableId = record[VARIABLE_WORKFLOW_HISTORY.VARIABLE_ID]!!,
  )

  constructor(
      row: VariableWorkflowHistoryRow
  ) : this(
      row.createdBy!!,
      row.createdTime!!,
      row.feedback,
      row.id!!,
      row.internalComment,
      row.maxVariableValueId!!,
      row.projectId!!,
      row.variableWorkflowStatusId!!,
      row.variableId!!,
  )
}
