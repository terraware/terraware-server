package com.terraformation.backend.customer.api

import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.UserInternalInterestsStore
import com.terraformation.backend.db.accelerator.InternalInterest
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/users/{userId}/internalInterests")
@RestController
class UserInternalInterestsController(
    private val userInternalInterestsStore: UserInternalInterestsStore,
) {
  @ApiResponse200
  @GetMapping
  @Operation(summary = "Get the list of internal interests assigned to a user.")
  fun getUserDeliverableCategories(
      @PathVariable userId: UserId
  ): GetUserInternalInterestsResponsePayload {
    val internalInterests = userInternalInterestsStore.fetchForUser(userId)

    return GetUserInternalInterestsResponsePayload(internalInterests)
  }

  @ApiResponse200
  @Operation(summary = "Update which internal interests are assigned to a user.")
  @PutMapping
  fun updateUserDeliverableCategories(
      @PathVariable userId: UserId,
      @RequestBody payload: UpdateUserInternalInterestsRequestPayload,
  ): SimpleSuccessResponsePayload {
    userInternalInterestsStore.updateForUser(userId, payload.internalInterests)

    return SimpleSuccessResponsePayload()
  }
}

data class GetUserInternalInterestsResponsePayload(
    val internalInterests: Set<InternalInterest>,
) : SuccessResponsePayload

data class UpdateUserInternalInterestsRequestPayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "New set of category assignments. Existing assignments that aren't included " +
                        "here will be removed from the user."
            )
    )
    val internalInterests: Set<InternalInterest>,
)
