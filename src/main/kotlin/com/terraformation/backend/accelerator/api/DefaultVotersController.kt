package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.DefaultVoterStore
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse403
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/voters")
@RestController
class DefaultVotersController(private val store: DefaultVoterStore) {

  @ApiResponse200
  @ApiResponse403
  @GetMapping
  @Operation(
      summary = "Fetches a list of users as default voters.",
  )
  fun fetchesDefaultVoters(): GetDefaultVotersListResponsePayload {
    return GetDefaultVotersListResponsePayload(store.findAll())
  }

  @ApiResponse200
  @ApiResponse403
  @PutMapping
  @Operation(
      summary = "Assigns a list of users as default voters.",
  )
  fun upsertDefaultVoters(
      @RequestBody payload: UpdateDefaultVotersListRequestPayload,
  ): SimpleSuccessResponsePayload {
    payload.userIds.forEach { store.insert(it, payload.updateProjects) }
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse403
  @DeleteMapping
  @Operation(
      summary = "Removes a list of default voters.",
  )
  fun deleteDefaultVoters(
      @RequestBody payload: UpdateDefaultVotersListRequestPayload,
  ): SimpleSuccessResponsePayload {
    payload.userIds.forEach { store.delete(it, payload.updateProjects) }
    return SimpleSuccessResponsePayload()
  }
}

data class UpdateDefaultVotersListRequestPayload(
    val userIds: List<UserId>,
    @Schema(
        description =
            "A flag that must be set to true to propagate to current project voters. Otherwise, " +
                "default voters will only be added to future projects and phases.")
    val updateProjects: Boolean,
)

data class GetDefaultVotersListResponsePayload(val userIds: List<UserId>) : SuccessResponsePayload
