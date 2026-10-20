package com.terraformation.backend.customer.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.EmailNotificationSettingsStore
import com.terraformation.backend.customer.model.EmailNotificationSettingsModel
import com.terraformation.backend.db.default_schema.EmailNotificationCategory
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/users/me/emailNotifications")
@RestController
class EmailNotificationSettingsController(
    private val emailNotificationSettingsStore: EmailNotificationSettingsStore,
) {
  @ApiResponse200
  @GetMapping
  @Operation(
      summary = "Gets the current user's email notification settings.",
      description =
          "Only includes the categories that apply to the current user. Users only receive " +
              "email notifications in a category if they have opted into it and have also " +
              "enabled email notifications.",
  )
  fun getEmailNotificationSettings(): GetEmailNotificationSettingsResponsePayload {
    val model = emailNotificationSettingsStore.fetchForCurrentUser()

    return GetEmailNotificationSettingsResponsePayload(model)
  }

  @ApiResponse200
  @Operation(summary = "Updates the current user's email notification settings.")
  @PutMapping
  fun updateEmailNotificationSettings(
      @RequestBody payload: UpdateEmailNotificationSettingsRequestPayload
  ): SimpleSuccessResponsePayload {
    emailNotificationSettingsStore.updateForCurrentUser(
        payload.categories.associate { it.category to it.optedIn },
        payload.emailNotificationsEnabled,
    )

    return SimpleSuccessResponsePayload()
  }
}

data class EmailNotificationCategorySettingPayload(
    val category: EmailNotificationCategory,
    val optedIn: Boolean,
)

data class GetEmailNotificationSettingsResponsePayload(
    val categories: List<EmailNotificationCategorySettingPayload>,
    @Schema(
        description =
            "If false, the user doesn't receive email notifications regardless of which " +
                "categories they've opted into."
    )
    val emailNotificationsEnabled: Boolean,
) : SuccessResponsePayload {
  constructor(
      model: EmailNotificationSettingsModel
  ) : this(
      categories =
          model.categories.map { (category, optedIn) ->
            EmailNotificationCategorySettingPayload(category, optedIn)
          },
      emailNotificationsEnabled = model.emailNotificationsEnabled,
  )
}

data class UpdateEmailNotificationSettingsRequestPayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "Categories to opt into or out of. Categories that aren't included here are " +
                        "left as-is."
            )
    )
    val categories: List<EmailNotificationCategorySettingPayload> = emptyList(),
    @Schema(
        description =
            "If present, turns email notifications on or off regardless of category opt-ins. " +
                "If absent, leaves the current setting as-is."
    )
    val emailNotificationsEnabled: Boolean? = null,
)
