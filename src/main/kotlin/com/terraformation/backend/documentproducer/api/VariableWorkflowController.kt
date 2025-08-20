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
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.db.VariableWorkflowStore
import com.terraformation.backend.documentproducer.model.ExistingValue
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
    private val variableStore: VariableStore,
    private val variableValueStore: VariableValueStore,
    private val variableWorkflowStore: VariableWorkflowStore,
) {
  @Operation(summary = "Get the workflow history for a variable in a project.")
  @GetMapping("/{variableId}/history")
  fun getVariableWorkflowHistory(
      @PathVariable projectId: ProjectId,
      @PathVariable variableId: VariableId,
  ): GetVariableWorkflowHistoryResponsePayload {
    val variable = variableStore.fetchOneVariable(variableId)
    val historyModels = variableWorkflowStore.fetchProjectVariableHistory(projectId, variableId)

    return GetVariableWorkflowHistoryResponsePayload(
        VariablePayload.of(variable),
        historyModels.map {
          val values =
              variableValueStore.listValues(
                  projectId = projectId,
                  maxValueId = it.maxVariableValueId,
                  variableIds = setOf(variableId),
              )
          VariableWorkflowHistoryElement(it, values)
        },
    )
  }

  @Operation(summary = "Update the workflow details for a variable in a project.")
  @PutMapping("/{variableId}")
  fun updateVariableWorkflowDetails(
      @PathVariable projectId: ProjectId,
      @PathVariable variableId: VariableId,
      @RequestBody payload: UpdateVariableWorkflowDetailsRequestPayload,
  ): SimpleSuccessResponsePayload {
    variableWorkflowStore.update(projectId, variableId) {
      it.copy(
          status = payload.status,
          feedback = payload.feedback,
          internalComment = payload.internalComment,
      )
    }

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
    val variableValues: List<ExistingValuePayload>,
) {
  constructor(
      model: ExistingVariableWorkflowHistoryModel,
      values: List<ExistingValue>,
  ) : this(
      model.createdBy,
      model.createdTime,
      model.feedback,
      model.id,
      model.internalComment,
      model.maxVariableValueId,
      model.projectId,
      model.status,
      values.map { ExistingValuePayload.of(it) },
  )
}

data class GetVariableWorkflowHistoryResponsePayload(
    val variable: VariablePayload,
    val history: List<VariableWorkflowHistoryElement>,
) : SuccessResponsePayload

data class UpdateVariableWorkflowDetailsRequestPayload(
    val feedback: String?,
    val internalComment: String?,
    val status: VariableWorkflowStatus,
)
