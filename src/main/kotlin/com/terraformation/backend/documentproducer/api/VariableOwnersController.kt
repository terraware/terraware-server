package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.documentproducer.db.VariableOwnerStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/document-producer/projects/{projectId}/owners")
@RestController
class VariableOwnersController(
    private val variableOwnerStore: VariableOwnerStore,
) {
  @GetMapping
  @Operation(
      summary = "List the owners of a project's variables.",
      description = "Only variables that actually have owners are returned.",
  )
  fun listVariableOwners(@PathVariable projectId: ProjectId): ListVariableOwnersResponsePayload {
    val owners = variableOwnerStore.listOwners(projectId)

    return ListVariableOwnersResponsePayload(
        owners.entries.map { VariableOwnersResponseElement(it.value, it.key) }
    )
  }

  @PutMapping("/{variableId}")
  @Operation(summary = "Update or remove the owner of a variable in a project.")
  fun updateVariableOwner(
      @PathVariable projectId: ProjectId,
      @PathVariable variableId: VariableId,
      @RequestBody payload: UpdateVariableOwnerRequestPayload,
  ): SimpleSuccessResponsePayload {
    variableOwnerStore.updateOwner(projectId, variableId, payload.ownedBy)

    return SimpleSuccessResponsePayload()
  }
}

data class VariableOwnersResponseElement(
    val ownedBy: UserId,
    val variableId: VariableId,
)

data class ListVariableOwnersResponsePayload(val variables: List<VariableOwnersResponseElement>) :
    SuccessResponsePayload

data class UpdateVariableOwnerRequestPayload(
    @Schema(
        description = "New owner of the variable, or null if the variable should have no owner."
    )
    val ownedBy: UserId?
)
