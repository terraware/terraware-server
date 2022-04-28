package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.model.NotificationCountModel
import com.terraformation.backend.customer.model.NotificationModel
import com.terraformation.backend.db.NotificationCriticality
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.NotificationNotFoundException
import com.terraformation.backend.db.NotificationType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.net.URI
import java.time.Clock
import java.time.Instant
import javax.validation.Valid
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
class NotificationController(
    private val notificationStore: NotificationStore,
    private val clock: Clock
) {

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @GetMapping("/{id}")
  @Operation(summary = "Retrieve a notification by its id.")
  fun read(
      @PathVariable("id") notificationId: NotificationId,
  ): GetNotificationResponsePayload {
    val notification =
        notificationStore.fetchById(notificationId)
            ?: throw NotificationNotFoundException(notificationId)
    return GetNotificationResponsePayload(NotificationPayload(notification))
  }

  @ApiResponse(responseCode = "200")
  @GetMapping()
  @Operation(summary = "Retrieve all notifications for current user scoped to an organization.")
  fun readAll(
      @RequestParam("organizationId", required = false)
      @Schema(description = "If set, return notifications relevant to that organization.")
      organizationId: OrganizationId?
  ): GetNotificationsResponsePayload {
    val notifications = notificationStore.fetchByOrganization(organizationId)
    return GetNotificationsResponsePayload(notifications.map { NotificationPayload(it) })
  }

  @ApiResponse(responseCode = "200")
  @GetMapping("/count")
  @Operation(summary = "Retrieve notifications count by organization for current user.")
  fun count(): GetNotificationsCountResponsePayload {
    val notifications = notificationStore.count()
    return GetNotificationsCountResponsePayload(notifications.map { NotificationCountPayload(it) })
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @PutMapping("/{id}")
  @Operation(summary = "Update a single notification as read or unread")
  fun markRead(
      @PathVariable("id") notificationId: NotificationId,
      @RequestBody @Valid payload: UpdateNotificationRequestPayload
  ): SimpleSuccessResponsePayload {
    if (!notificationStore.markRead(
        notificationId, clock.instant().takeIf { payload.read }, payload.organizationId)) {
      throw NotificationNotFoundException(notificationId)
    }
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse(responseCode = "200")
  @PutMapping()
  @Operation(summary = "Update notifications as read or unread")
  fun markAllRead(
      @RequestBody @Valid payload: UpdateNotificationRequestPayload
  ): SimpleSuccessResponsePayload {
    notificationStore.markAllRead(clock.instant().takeIf { payload.read }, payload.organizationId)
    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema
data class NotificationPayload(
    val id: NotificationId,
    val notificationType: NotificationType,
    val notificationCriticality: NotificationCriticality,
    val userId: UserId,
    val organizationId: OrganizationId?,
    val metadata: Map<String, Any?>,
    val localUrl: URI,
    val createdTime: Instant,
    val readTime: Instant?,
) {
  constructor(
      model: NotificationModel
  ) : this(
      model.id,
      model.notificationType,
      model.notificationType.notificationCriticalityId,
      model.userId,
      model.organizationId,
      model.metadata,
      model.localUrl,
      model.createdTime,
      model.readTime,
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

data class UpdateNotificationRequestPayload(val read: Boolean, val organizationId: OrganizationId?)

data class GetNotificationResponsePayload(val notification: NotificationPayload) :
    SuccessResponsePayload

data class GetNotificationsResponsePayload(val notifications: List<NotificationPayload>) :
    SuccessResponsePayload

data class GetNotificationsCountResponsePayload(val notifications: List<NotificationCountPayload>) :
    SuccessResponsePayload
