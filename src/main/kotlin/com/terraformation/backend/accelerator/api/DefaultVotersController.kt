package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.DefaultVoterStore
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse403
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/voters")
@RestController
class DefaultVotersController(private val store: DefaultVoterStore) {

  @ApiResponse200
  @ApiResponse403
  @DeleteMapping
  @Operation(
      summary = "Fetches a list of users as default voters.",
  )
  fun fetchesDefaultVoters(): DefaultVotersListResponsePayload {
    return DefaultVotersListResponsePayload(store.findAll())
  }

  @ApiResponse200
  @ApiResponse403
  @DeleteMapping
  @Operation(
      summary = "Assigns a list of users as default voters.",
  )
  fun upsertDefaultVoters(
      @RequestBody payload: DefaultVotersListRequestPayload,
  ): SimpleSuccessResponsePayload {
    payload.userIds.forEach { store.insert(it) }
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse403
  @DeleteMapping
  @Operation(
      summary = "Removes a list of default voters.",
  )
  fun deleteDefaultVoters(
      @RequestBody payload: DefaultVotersListRequestPayload,
  ): SimpleSuccessResponsePayload {
    payload.userIds.forEach { store.delete(it) }
    return SimpleSuccessResponsePayload()
  }
}

data class DefaultVotersListRequestPayload(val userIds: List<UserId>)

data class DefaultVotersListResponsePayload(val userIds: List<UserId>) : SuccessResponsePayload
