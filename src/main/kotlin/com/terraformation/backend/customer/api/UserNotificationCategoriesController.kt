package com.terraformation.backend.customer.api

import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.UserNotificationCategoriesService
import com.terraformation.backend.customer.model.UserNotificationCategory
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
@RequestMapping("/api/v1/users/{userId}/notificationCategories")
@RestController
class UserNotificationCategoriesController(
    private val userNotificationCategoriesService: UserNotificationCategoriesService,
) {
  @ApiResponse200
  @GetMapping
  @Operation(summary = "Get the list of deliverable categories assigned to a user.")
  fun getUserDeliverableCategories(
      @PathVariable userId: UserId
  ): GetUserNotificationCategoriesResponsePayload {
    val categories = userNotificationCategoriesService.get(userId)

    return GetUserNotificationCategoriesResponsePayload(categories)
  }

  @ApiResponse200
  @Operation(summary = "Update which deliverable categories are assigned to a user.")
  @PutMapping
  fun updateUserDeliverableCategories(
      @PathVariable userId: UserId,
      @RequestBody payload: UpdateUserNotificationCategoriesRequestPayload,
  ): SimpleSuccessResponsePayload {
    userNotificationCategoriesService.update(userId, payload.categories)

    return SimpleSuccessResponsePayload()
  }
}

data class GetUserNotificationCategoriesResponsePayload(
    val categories: Set<UserNotificationCategory>,
) : SuccessResponsePayload

data class UpdateUserNotificationCategoriesRequestPayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "New set of category assignments. Existing assignments that aren't included " +
                        "here will be removed from the user."))
    val categories: Set<UserNotificationCategory>,
)
