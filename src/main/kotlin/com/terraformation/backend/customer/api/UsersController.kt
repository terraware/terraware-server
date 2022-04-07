package com.terraformation.backend.customer.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import javax.ws.rs.ForbiddenException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/users")
@RestController
class UsersController(private val userStore: UserStore) {
  @GetMapping("/me")
  @Operation(summary = "Gets information about the current user.")
  fun getMyself(): GetUserResponsePayload {
    val user = currentUser()
    if (user is IndividualUser) {
      return GetUserResponsePayload(
          UserProfilePayload(
              user.userId,
              user.email,
              user.emailNotificationsEnabled,
              user.firstName,
              user.lastName))
    } else {
      throw ForbiddenException("Only ordinary users can request their information")
    }
  }

  @PutMapping("/me")
  @Operation(summary = "Updates information about the current user.")
  fun updateMyself(@RequestBody payload: UpdateUserRequestPayload): SimpleSuccessResponsePayload {
    val user = currentUser()
    if (user is IndividualUser) {
      val model =
          user.copy(
              emailNotificationsEnabled = payload.emailNotificationsEnabled
                      ?: user.emailNotificationsEnabled,
              firstName = payload.firstName,
              lastName = payload.lastName)
      userStore.updateUser(model)
      return SimpleSuccessResponsePayload()
    } else {
      throw ForbiddenException("Can only update information about ordinary users")
    }
  }
}

data class UserProfilePayload(
    @Schema(
        description =
            "User's unique ID. This should not be shown to the user, but is a required input to " +
                "some API endpoints.")
    val id: UserId,
    val email: String,
    @Schema(
        description =
            "If true, the user wants to receive all the notifications for their organization and " +
                "projects via email. This does not apply to certain kinds of notifications such " +
                "as \"You've been added to a new organization.\"")
    val emailNotificationsEnabled: Boolean,
    val firstName: String?,
    val lastName: String?,
)

data class GetUserResponsePayload(val user: UserProfilePayload) : SuccessResponsePayload

data class UpdateUserRequestPayload(
    @Schema(
        description =
            "If true, the user wants to receive all the notifications for their organization and " +
                "projects via email. This does not apply to certain kinds of notifications such " +
                "as \"You've been added to a new organization.\" If null, leave the existing " +
                "value as-is.")
    val emailNotificationsEnabled: Boolean? = null,
    val firstName: String,
    val lastName: String,
)
