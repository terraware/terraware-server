package com.terraformation.backend.customer.api

import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse400
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.UserService
import com.terraformation.backend.customer.db.UserInternalInterestsStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.accelerator.InternalInterest
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
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
    private val userService: UserService,
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
      @PathVariable userId: UserId,
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

  @ApiResponse200
  @ApiResponse400
  @PostMapping("/globalRoles/invite")
  @Operation(
      summary = "Invite a new accelerator admin by email and assign global roles.",
      description =
          "If the email is unknown, creates an unregistered user row and emails a registration " +
              "link. If the email already maps to a user, the user's global roles are replaced " +
              "with the supplied set and no email is sent.",
  )
  fun inviteGlobalRolesUser(
      @RequestBody @Valid payload: InviteGlobalRolesUserRequestPayload,
  ): GetGlobalRolesUserResponsePayload {
    val user = userService.inviteAdmin(payload.email, payload.globalRoles)
    val internalInterests = userInternalInterestsStore.fetchForUser(user.userId)

    return GetGlobalRolesUserResponsePayload(UserWithGlobalRolesPayload(user, internalInterests))
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

data class GetGlobalRolesUserResponsePayload(val user: UserWithGlobalRolesPayload) :
    SuccessResponsePayload

data class DeleteGlobalRolesRequestPayload(
    val userIds: Set<UserId>,
)

data class UpdateGlobalRolesRequestPayload(
    val globalRoles: Set<GlobalRole>,
)

data class InviteGlobalRolesUserRequestPayload(
    @NotEmpty val email: String,
    @NotEmpty val globalRoles: Set<GlobalRole>,
)
