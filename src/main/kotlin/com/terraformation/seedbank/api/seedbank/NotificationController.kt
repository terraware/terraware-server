package com.terraformation.seedbank.api.seedbank

import com.terraformation.seedbank.api.SimpleSuccessResponsePayload
import com.terraformation.seedbank.api.SuccessResponsePayload
import com.terraformation.seedbank.api.annotation.ApiResponse404
import com.terraformation.seedbank.api.annotation.ApiResponseSimpleSuccess
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/notification")
@RestController
@SeedBankAppEndpoint
class NotificationController {
  @ApiResponse(
      responseCode = "200", description = "Notifications in reverse time order (newest first).")
  @GetMapping
  @Operation(summary = "Get a list of recent notifications")
  fun listAll(
      @Parameter(description = "Don't return notifications older than this; default is 1 month ago")
      @RequestParam
      since: Instant? = null,
      @Parameter(description = "Return at most this many notifications; default is no limit")
      @RequestParam
      limit: Int? = null
  ): NotificationListResponse {
    return NotificationListResponse(
        listOf(
            NotificationPayload(
                "xxxxx", Instant.now(), NotificationType.Alert, false, "An example alert"),
            NotificationPayload(
                "yyyyy",
                Instant.now().minusSeconds(3600),
                NotificationType.State,
                false,
                "An example state notification"),
            NotificationPayload(
                "zzzzz",
                Instant.now().minusSeconds(7200),
                NotificationType.Date,
                true,
                "An example date notification that is already read")))
  }

  @ApiResponse404(description = "The requested notification ID was not valid.")
  @ApiResponseSimpleSuccess(description = "Notification has been marked as read.")
  @Operation(summary = "Mark a specific notification as read.")
  @PostMapping("/{id}/markRead")
  @ResponseBody
  fun markRead(
      @Parameter(description = "ID of notification to mark as read") @PathVariable id: String
  ): SimpleSuccessResponsePayload {
    if (id == "0") {
      throw RuntimeException("Notification $id not found")
    }
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse404
  @ApiResponseSimpleSuccess(description = "All notifications have been marked as read.")
  @Operation(summary = "Mark all unread notifications as read.")
  @PostMapping("/all/markRead")
  @ResponseBody
  fun markAllRead(): SimpleSuccessResponsePayload {
    return SimpleSuccessResponsePayload()
  }
}

enum class NotificationType {
  Alert,
  State,
  Date
}

data class NotificationPayload(
    @Schema(
        description = "Unique identifier for this notification. Clients should treat it as opaque.",
        example = "12345")
    val id: String,
    val timestamp: Instant,
    val type: NotificationType,
    @Schema(description = "If true, this notification has been marked as read.") val read: Boolean,
    @Schema(
        description = "Plain-text body of notification.",
        example = "Accession XYZ is ready to be dried.")
    val text: String
)

data class NotificationListResponse(val notifications: List<NotificationPayload>) :
    SuccessResponsePayload
