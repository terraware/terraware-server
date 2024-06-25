package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.documentproducer.db.VariableWorkflowStore
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/document-producer/projects/{projectId}/workflow")
@RestController
class VariableWorkflowController(private val variableWorkflowStore: VariableWorkflowStore) {
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

data class UpdateVariableWorkflowDetailsRequestPayload(
    val feedback: String?,
    val internalComment: String?,
    val status: VariableWorkflowStatus,
)
