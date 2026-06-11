package com.terraformation.backend.plantingmanagement.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationGroupModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationType
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonNotificationsService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@TrackingEndpoint
@RequestMapping("/api/v1/planting-seasons")
@RestController
class PlantingSeasonNotificationsController(
    private val plantingSeasonNotificationsService: PlantingSeasonNotificationsService,
    private val messages: Messages,
) {

  @ApiResponse200
  @Operation(summary = "Lists all notifications for an organization")
  @GetMapping("/notifications")
  fun getPlantingSeasonNotifications(
      @RequestParam("organizationId") organizationId: OrganizationId,
      @RequestParam("notificationCategory")
      notificationCategory: PlantingSeasonNotificationCategory,
  ): GetPlantingSeasonNotificationsResponsePayload {
    val notifications =
        when (notificationCategory) {
          PlantingSeasonNotificationCategory.InventoryPlanning ->
              plantingSeasonNotificationsService
                  .getInventoryPlanningNotifications(organizationId)
                  .map { PlantingSeasonNotificationGroupPayload(it) }
          PlantingSeasonNotificationCategory.PlantingSeasonPlanning -> emptyList()
        }

    return GetPlantingSeasonNotificationsResponsePayload(notifications)
  }
}

enum class PlantingSeasonNotificationCategory {
  InventoryPlanning,
  PlantingSeasonPlanning,
}

data class GetPlantingSeasonNotificationsResponsePayload(
    val notifications: List<PlantingSeasonNotificationGroupPayload>
) : SuccessResponsePayload

data class PlantingSeasonNotificationPayload(
    val type: PlantingSeasonNotificationType,
    val speciesScientificNames: Set<String>? = null,
) {
  constructor(
      model: PlantingSeasonNotificationModel
  ) : this(
      type = model.type,
      speciesScientificNames = model.speciesScientificNames,
  )
}

data class PlantingSeasonNotificationGroupPayload(
    val plantingSeasonId: PlantingSeasonId,
    val plantingSeasonName: String,
    val plantingSiteName: String,
    val lastEventLogId: EventLogId,
    val notifications: List<PlantingSeasonNotificationPayload>,
) {
  constructor(
      model: PlantingSeasonNotificationGroupModel
  ) : this(
      plantingSeasonId = model.plantingSeasonId,
      plantingSeasonName = model.plantingSeasonName,
      plantingSiteName = model.plantingSiteName,
      lastEventLogId = model.lastEventLogId,
      notifications = model.notifications.map { PlantingSeasonNotificationPayload(it) },
  )
}
