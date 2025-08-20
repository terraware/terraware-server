package com.terraformation.backend.customer.api

import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.UserInternalInterestsStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.accelerator.InternalInterest
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import java.time.Instant
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1")
@RestController
class GlobalRolesController(
    private val userInternalInterestsStore: UserInternalInterestsStore,
    private val userStore: UserStore,
) {
  @ApiResponse200
  @GetMapping("/globalRoles/users")
  @Operation(summary = "Gets the list of users that have global roles.")
  fun listGlobalRoles(): GlobalRoleUsersListResponsePayload =
      GlobalRoleUsersListResponsePayload(
          userStore.fetchWithGlobalRoles().map { user ->
            UserWithGlobalRolesPayload(user, userInternalInterestsStore.fetchForUser(user.userId))
          }
      )

  @ApiResponse200
  @ApiResponse404
  @PostMapping("/users/{userId}/globalRoles")
  @Operation(summary = "Apply the supplied global roles to the user.")
  fun updateGlobalRoles(
      @PathVariable("userId") userId: UserId,
      @RequestBody payload: UpdateGlobalRolesRequestPayload,
  ): SuccessResponsePayload {
    userStore.updateGlobalRoles(setOf(userId), payload.globalRoles)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404
  @DeleteMapping("/globalRoles/users")
  @Operation(summary = "Remove global roles from the supplied users.")
  fun deleteGlobalRoles(
      @RequestBody payload: DeleteGlobalRolesRequestPayload,
  ): SuccessResponsePayload {
    userStore.updateGlobalRoles(payload.userIds, emptySet())

    return SimpleSuccessResponsePayload()
  }
}

data class UserWithGlobalRolesPayload(
    val createdTime: Instant,
    val internalInterests: Set<InternalInterest>,
    val id: UserId,
    val email: String,
    val firstName: String?,
    val globalRoles: Set<GlobalRole>,
    val lastName: String?,
) {
  constructor(
      user: IndividualUser,
      internalInterests: Set<InternalInterest>,
  ) : this(
      createdTime = user.createdTime,
      internalInterests = internalInterests,
      id = user.userId,
      email = user.email,
      firstName = user.firstName,
      globalRoles = user.globalRoles,
      lastName = user.lastName,
  )
}

data class GlobalRoleUsersListResponsePayload(val users: List<UserWithGlobalRolesPayload>) :
    SuccessResponsePayload

data class DeleteGlobalRolesRequestPayload(
    val userIds: Set<UserId>,
)

data class UpdateGlobalRolesRequestPayload(
    val globalRoles: Set<GlobalRole>,
)
