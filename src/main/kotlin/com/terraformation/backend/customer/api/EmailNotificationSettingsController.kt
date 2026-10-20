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
      summary = "Gets the current user's email notification category settings.",
      description =
          "Only includes the categories that apply to the current user. Users only receive " +
              "email notifications in a category if they have opted into it and have also " +
              "enabled email notifications in their user profile.",
  )
  fun getEmailNotificationSettings(): GetEmailNotificationSettingsResponsePayload {
    val model = emailNotificationSettingsStore.fetchForCurrentUser()

    return GetEmailNotificationSettingsResponsePayload(model)
  }

  @ApiResponse200
  @Operation(summary = "Opts the current user into or out of email notification categories.")
  @PutMapping
  fun updateEmailNotificationSettings(
      @RequestBody payload: UpdateEmailNotificationSettingsRequestPayload
  ): SimpleSuccessResponsePayload {
    emailNotificationSettingsStore.updateForCurrentUser(
        payload.categories.associate { it.category to it.optedIn }
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
) : SuccessResponsePayload {
  constructor(
      model: EmailNotificationSettingsModel
  ) : this(
      model.categories.map { (category, optedIn) ->
        EmailNotificationCategorySettingPayload(category, optedIn)
      }
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
    val categories: List<EmailNotificationCategorySettingPayload>,
)
