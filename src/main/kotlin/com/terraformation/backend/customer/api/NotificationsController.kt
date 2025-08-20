package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.model.NotificationCountModel
import com.terraformation.backend.customer.model.NotificationModel
import com.terraformation.backend.db.default_schema.NotificationCriticality
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import java.net.URI
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/notifications")
@RestController
class NotificationsController(private val notificationStore: NotificationStore) {

  /** Retrieves a notification by its id */
  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @GetMapping("/{id}")
  @Operation(summary = "Retrieve a notification by its id.")
  fun read(
      @PathVariable("id") notificationId: NotificationId,
  ): GetNotificationResponsePayload {
    val notification = notificationStore.fetchById(notificationId)
    return GetNotificationResponsePayload(NotificationPayload(notification))
  }

  /**
   * Retrieves notifications specific to an organization. If organization id is unset, globally
   * scoped notifications will be retrieved.
   */
  @ApiResponse(responseCode = "200")
  @GetMapping()
  @Operation(summary = "Retrieve all notifications for current user scoped to an organization.")
  fun readAll(
      @RequestParam
      @Schema(description = "If set, return notifications relevant to that organization.")
      organizationId: OrganizationId?
  ): GetNotificationsResponsePayload {
    val notifications = notificationStore.fetchByOrganization(organizationId)
    return GetNotificationsResponsePayload(notifications.map { NotificationPayload(it) })
  }

  /**
   * Retrieves list of organizations with count of unread notifications, organizations with no
   * unread notifications will not be included in the list.
   */
  @ApiResponse(responseCode = "200")
  @GetMapping("/count")
  @Operation(summary = "Retrieve notifications count by organization for current user.")
  fun count(): GetNotificationsCountResponsePayload {
    val notifications = notificationStore.count()
    return GetNotificationsCountResponsePayload(notifications.map { NotificationCountPayload(it) })
  }

  /** Marks a notification by id as read or unread */
  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @PutMapping("/{id}")
  @Operation(summary = "Update a single notification as read or unread")
  fun markRead(
      @PathVariable("id") notificationId: NotificationId,
      @RequestBody @Valid payload: UpdateNotificationRequestPayload,
  ): SimpleSuccessResponsePayload {
    notificationStore.markRead(payload.read, notificationId)
    return SimpleSuccessResponsePayload()
  }

  /**
   * Marks all user's notifications as read or unread, scoped by organization. If organization id is
   * unset, this api will apply to globally scoped notifications.
   */
  @ApiResponse(responseCode = "200")
  @PutMapping()
  @Operation(summary = "Update notifications as read or unread")
  fun markAllRead(
      @RequestBody @Valid payload: UpdateNotificationsRequestPayload
  ): SimpleSuccessResponsePayload {
    notificationStore.markAllRead(payload.read, payload.organizationId)
    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema
data class NotificationPayload(
    val id: NotificationId,
    val notificationCriticality: NotificationCriticality,
    val organizationId: OrganizationId?,
    val title: String,
    val body: String,
    val localUrl: URI,
    val createdTime: Instant,
    val isRead: Boolean,
) {
  constructor(
      model: NotificationModel
  ) : this(
      model.id,
      model.notificationType.notificationCriticalityId,
      model.organizationId,
      model.title,
      model.body,
      model.localUrl,
      model.createdTime,
      model.isRead,
  )
}

data class NotificationCountPayload(val organizationId: OrganizationId?, val unread: Int) {
  constructor(
      notificationCount: NotificationCountModel
  ) : this(
      notificationCount.organizationId,
      notificationCount.unread,
  )
}

data class UpdateNotificationRequestPayload(val read: Boolean)

data class UpdateNotificationsRequestPayload(
    val read: Boolean,
    val organizationId: OrganizationId?,
)

data class GetNotificationResponsePayload(val notification: NotificationPayload) :
    SuccessResponsePayload

data class GetNotificationsResponsePayload(val notifications: List<NotificationPayload>) :
    SuccessResponsePayload

data class GetNotificationsCountResponsePayload(val notifications: List<NotificationCountPayload>) :
    SuccessResponsePayload
