package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ArbitraryJsonObject
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.FunderUser
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.UserNotFoundForEmailException
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpSession
import jakarta.ws.rs.ForbiddenException
import java.time.Instant
import java.time.InstantSource
import java.time.ZoneId
import java.util.Locale
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/users")
@RestController
class UsersController(private val clock: InstantSource, private val userStore: UserStore) {
  @GetMapping("/me")
  @Operation(summary = "Gets information about the current user.")
  fun getMyself(): GetUserResponsePayload {
    val user = currentUser()
    return GetUserResponsePayload(UserProfilePayload(user))
  }

  @PutMapping("/me")
  @Operation(summary = "Updates information about the current user.")
  fun updateMyself(@RequestBody payload: UpdateUserRequestPayload): SimpleSuccessResponsePayload {
    val user = currentUser()
    if (user is IndividualUser) {
      val model =
          user.copy(
              countryCode = payload.countryCode,
              emailNotificationsEnabled =
                  payload.emailNotificationsEnabled ?: user.emailNotificationsEnabled,
              firstName = payload.firstName,
              lastName = payload.lastName,
              locale = payload.locale?.let { Locale.forLanguageTag(it) },
              timeZone = payload.timeZone,
          )
      userStore.updateUser(model)
      return SimpleSuccessResponsePayload()
    } else if (user is FunderUser) {
      val model =
          user.copy(
              countryCode = payload.countryCode,
              emailNotificationsEnabled =
                  payload.emailNotificationsEnabled ?: user.emailNotificationsEnabled,
              firstName = payload.firstName,
              lastName = payload.lastName,
              locale = payload.locale?.let { Locale.forLanguageTag(it) },
              timeZone = payload.timeZone,
          )
      userStore.updateUser(model)
      return SimpleSuccessResponsePayload()
    } else {
      throw ForbiddenException("Can only update information about ordinary users")
    }
  }

  @DeleteMapping("/me")
  @Operation(
      summary = "Deletes the current user's account.",
      description = "WARNING! This operation is not reversible.",
  )
  fun deleteMyself(session: HttpSession?): SimpleSuccessResponsePayload {
    userStore.deleteSelf()
    session?.invalidate()
    return SimpleSuccessResponsePayload()
  }

  @PutMapping("/me/cookies")
  @Operation(summary = "Updates the current user's cookie consent selection.")
  fun updateCookieConsent(
      @RequestBody payload: UpdateUserCookieConsentRequestPayload
  ): SimpleSuccessResponsePayload {
    val user = currentUser()
    if (user is IndividualUser) {
      val model =
          user.copy(
              cookiesConsented = payload.cookiesConsented,
              cookiesConsentedTime = clock.instant(),
          )

      userStore.updateUser(model)
      return SimpleSuccessResponsePayload()
    } else if (user is FunderUser) {
      val model =
          user.copy(
              cookiesConsented = payload.cookiesConsented,
              cookiesConsentedTime = clock.instant(),
          )

      userStore.updateUser(model)
      return SimpleSuccessResponsePayload()
    } else {
      throw ForbiddenException("Can only update cookie consent for ordinary users")
    }
  }

  @GetMapping("/me/preferences")
  @Operation(summary = "Gets the current user's preferences.")
  fun getUserPreferences(
      @RequestParam
      @Schema(
          description =
              "If present, get the user's per-organization preferences for this organization. " +
                  "If not present, get the user's global preferences."
      )
      organizationId: OrganizationId?
  ): GetUserPreferencesResponsePayload {
    val preferences = userStore.fetchPreferences(organizationId)
    return GetUserPreferencesResponsePayload(preferences)
  }

  @PutMapping("/me/preferences")
  @Operation(summary = "Updates the current user's preferences.")
  fun updateUserPreferences(
      @RequestBody payload: UpdateUserPreferencesRequestPayload
  ): SimpleSuccessResponsePayload {
    userStore.updatePreferences(payload.organizationId, payload.preferences)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping
  @Operation(summary = "Gets a user by some criteria, for now only email is available")
  fun searchUsers(
      @RequestParam
      @Schema(description = "The email to use when searching for a user")
      email: String
  ): GetUserResponsePayload {
    val user = userStore.fetchByEmailAccelerator(email)
    if (user != null) {
      return GetUserResponsePayload(UserProfilePayload(user))
    }

    throw UserNotFoundForEmailException(email)
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{userId}")
  @Operation(summary = "Get a user by ID, if they exist.")
  fun getUser(
      @PathVariable("userId") userId: UserId,
  ): GetUserResponsePayload {
    val user = userStore.fetchOneByIdAccelerator(userId)
    return GetUserResponsePayload(UserProfilePayload(user))
  }
}

data class UserProfilePayload(
    @Schema(
        description =
            "If true, the user has consented to the use of analytics cookies. If false, the " +
                "user has declined. If null, the user has not made a consent selection yet."
    )
    val cookiesConsented: Boolean?,
    @Schema(
        description =
            "If the user has selected whether or not to consent to analytics cookies, the date " +
                "and time of the selection."
    )
    val cookiesConsentedTime: Instant?,
    @Schema(description = "Two-letter code of the user's country.", example = "US")
    val countryCode: String?,
    @Schema(
        description =
            "User's unique ID. This should not be shown to the user, but is a required input to " +
                "some API endpoints."
    )
    val id: UserId,
    val email: String,
    @Schema(
        description =
            "If true, the user wants to receive all the notifications for their organizations " +
                "via email. This does not apply to certain kinds of notifications such as " +
                "\"You've been added to a new organization.\""
    )
    val emailNotificationsEnabled: Boolean,
    val firstName: String?,
    val globalRoles: Set<GlobalRole>,
    val lastName: String?,
    @Schema(description = "IETF locale code containing user's preferred language.", example = "en")
    val locale: String?,
    val timeZone: ZoneId?,
    @Schema(description = "Type of User. Could be Individual, Funder or DeviceManager")
    val userType: UserType,
) {
  constructor(
      user: TerrawareUser
  ) : this(
      user.cookiesConsented,
      user.cookiesConsentedTime,
      user.countryCode,
      user.userId,
      user.email,
      user.emailNotificationsEnabled,
      user.firstName,
      user.globalRoles,
      user.lastName,
      user.locale?.toLanguageTag(),
      user.timeZone,
      user.userType,
  )
}

data class GetUserResponsePayload(val user: UserProfilePayload) : SuccessResponsePayload

data class UpdateUserRequestPayload(
    @Schema(description = "Two-letter code of the user's country.", example = "US")
    val countryCode: String?,
    @Schema(
        description =
            "If true, the user wants to receive all the notifications for their organizations " +
                "via email. This does not apply to certain kinds of notifications such as " +
                "\"You've been added to a new organization.\" If null, leave the existing value " +
                "as-is."
    )
    val emailNotificationsEnabled: Boolean? = null,
    val firstName: String,
    val lastName: String,
    @Schema(description = "IETF locale code containing user's preferred language.", example = "en")
    val locale: String?,
    val timeZone: ZoneId?,
)

data class UpdateUserCookieConsentRequestPayload(
    @Schema(
        description =
            "If true, the user consents to the use of analytics cookies. If false, they decline."
    )
    val cookiesConsented: Boolean,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class GetUserPreferencesResponsePayload(
    @Schema(description = "The user's preferences, or null if no preferences have been stored yet.")
    val preferences: ArbitraryJsonObject?
) : SuccessResponsePayload

data class UpdateUserPreferencesRequestPayload(
    @Schema(
        description =
            "If present, update the user's per-organization preferences for this organization. " +
                "If not present, update the user's global preferences."
    )
    val organizationId: OrganizationId?,
    val preferences: ArbitraryJsonObject,
)
