package com.terraformation.seedbank.api.seedbank

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.seedbank.api.NotFoundException
import com.terraformation.seedbank.api.SimpleSuccessResponsePayload
import com.terraformation.seedbank.api.SuccessResponsePayload
import com.terraformation.seedbank.api.annotation.ApiResponse404
import com.terraformation.seedbank.api.annotation.ApiResponseSimpleSuccess
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.db.NotificationFetcher
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.*

@RequestMapping("/api/v1/seedbank/notification")
@RestController
@SeedBankAppEndpoint
class NotificationController(private val notificationFetcher: NotificationFetcher) {
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
      limit: Long? = null
  ): NotificationListResponse {
    return NotificationListResponse(notificationFetcher.fetchSince(since, limit))
  }

  @ApiResponse404(description = "The requested notification ID was not valid.")
  @ApiResponseSimpleSuccess(description = "Notification has been marked as read.")
  @Operation(summary = "Mark a specific notification as read.")
  @PostMapping("/{id}/markRead")
  @ResponseBody
  fun markRead(
      @Parameter(description = "ID of notification to mark as read") @PathVariable id: String
  ): SimpleSuccessResponsePayload {
    val notificationId = id.toLongOrNull()
    if (notificationId != null && notificationFetcher.markRead(notificationId)) {
      return SimpleSuccessResponsePayload()
    } else {
      throw NotFoundException("Notification not found.")
    }
  }

  @ApiResponseSimpleSuccess(description = "All notifications have been marked as read.")
  @Operation(summary = "Mark all notifications as read.")
  @PostMapping("/all/markRead")
  @ResponseBody
  fun markAllRead(): SimpleSuccessResponsePayload {
    notificationFetcher.markAllRead()
    return SimpleSuccessResponsePayload()
  }
}

@Profile("default", "apidoc")
@RequestMapping("/api/v1/seedbank/notification")
@RestController
@SeedBankAppEndpoint
class NotificationDevController(private val notificationFetcher: NotificationFetcher) {
  @ApiResponseSimpleSuccess(description = "All notifications have been marked as unread.")
  @Operation(
      summary = "Mark all notifications as unread.",
      description = "For development and testing of notifications. Not available in production.")
  @PostMapping("/all/markUnread")
  @ResponseBody
  fun markAllUnread(): SimpleSuccessResponsePayload {
    notificationFetcher.markAllUnread()
    return SimpleSuccessResponsePayload()
  }
}

enum class NotificationType {
  Alert,
  State,
  Date
}

@JsonInclude(JsonInclude.Include.NON_NULL)
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
    val text: String,
    @Schema(description = "For accession notifications, which accession caused the notification.")
    val accessionNumber: String? = null,
    @Schema(description = "For state notifications, which state is being summarized.")
    val state: String? = null
)

data class NotificationListResponse(val notifications: List<NotificationPayload>) :
    SuccessResponsePayload
