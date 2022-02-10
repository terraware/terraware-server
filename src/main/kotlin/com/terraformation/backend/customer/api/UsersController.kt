package com.terraformation.backend.customer.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.IndividualUser
import io.swagger.v3.oas.annotations.Operation
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
      return GetUserResponsePayload(UserProfilePayload(user.email, user.firstName, user.lastName))
    } else {
      throw ForbiddenException("Only ordinary users can request their information")
    }
  }

  @PutMapping("/me")
  @Operation(summary = "Updates information about the current user.")
  fun updateMyself(@RequestBody payload: UpdateUserRequestPayload): SimpleSuccessResponsePayload {
    val user = currentUser()
    if (user is IndividualUser) {
      val model = user.copy(firstName = payload.firstName, lastName = payload.lastName)
      userStore.updateUser(model)
      return SimpleSuccessResponsePayload()
    } else {
      throw ForbiddenException("Can only update information about ordinary users")
    }
  }
}

data class UserProfilePayload(
    val email: String,
    val firstName: String?,
    val lastName: String?,
)

data class GetUserResponsePayload(val user: UserProfilePayload) : SuccessResponsePayload

data class UpdateUserRequestPayload(
    val firstName: String,
    val lastName: String,
)
