package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.VariableWorkflowHistoryId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.db.VariableWorkflowStore
import com.terraformation.backend.documentproducer.model.ExistingVariableWorkflowHistoryModel
import io.swagger.v3.oas.annotations.Operation
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/document-producer/projects/{projectId}/workflow")
@RestController
class VariableWorkflowController(
    private val variableValueStore: VariableValueStore,
    private val variableWorkflowStore: VariableWorkflowStore
) {
  @Operation(summary = "Get the workflow history for a variable in a project.")
  @GetMapping("/{variableId}")
  fun getVariableWorkflowHistory(
      @PathVariable projectId: ProjectId,
      @PathVariable variableId: VariableId,
  ): GetVariableWorkflowHistoryResponsePayload {
    val historyModels = variableWorkflowStore.fetchProjectVariableHistory(projectId, variableId)

    return GetVariableWorkflowHistoryResponsePayload(
        historyModels.map { VariableWorkflowHistoryElement(it) })
  }

  @Operation(summary = "Update the workflow details for a variable in a project.")
  @PutMapping("/{variableId}")
  fun updateVariableWorkflowDetails(
      @PathVariable projectId: ProjectId,
      @PathVariable variableId: VariableId,
      @RequestBody payload: UpdateVariableWorkflowDetailsRequestPayload
  ): SimpleSuccessResponsePayload {
    variableWorkflowStore.update(
        projectId, variableId, payload.status, payload.feedback, payload.internalComment)

    return SimpleSuccessResponsePayload()
  }
}

data class VariableWorkflowHistoryElement(
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
      model: ExistingVariableWorkflowHistoryModel
  ) : this(
      model.createdBy,
      model.createdTime,
      model.feedback,
      model.id,
      model.internalComment,
      model.maxVariableValueId,
      model.projectId,
      model.status,
      model.variableId,
  )
}

data class GetVariableWorkflowHistoryResponsePayload(
    val history: List<VariableWorkflowHistoryElement>,
) : SuccessResponsePayload

data class UpdateVariableWorkflowDetailsRequestPayload(
    val feedback: String?,
    val internalComment: String?,
    val status: VariableWorkflowStatus,
)
