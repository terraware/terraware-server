package com.terraformation.backend.plantingmanagement.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationAlertModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationModel
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
      @RequestParam("notificationType") notificationType: PlantingSeasonNotificationType,
  ): GetPlantingSeasonNotificationsResponsePayload {
    val notifications =
        when (notificationType) {
          PlantingSeasonNotificationType.InventoryPlanning ->
              plantingSeasonNotificationsService
                  .getInventoryPlanningNotifications(organizationId)
                  .map { PlantingSeasonNotificationPayload(it) }
          PlantingSeasonNotificationType.PlantingSeasonPlanning -> emptyList()
        }

    return GetPlantingSeasonNotificationsResponsePayload(notifications)
  }
}

enum class PlantingSeasonNotificationType {
  InventoryPlanning,
  PlantingSeasonPlanning,
}

data class GetPlantingSeasonNotificationsResponsePayload(
    val notifications: List<PlantingSeasonNotificationPayload>
) : SuccessResponsePayload

data class PlantingSeasonNotificationAlertPayload(
    val type: com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationType,
    val speciesScientificNames: Set<String>? = null,
) {
  constructor(
      model: PlantingSeasonNotificationAlertModel
  ) : this(
      type = model.type,
      speciesScientificNames = model.speciesScientificNames,
  )
}

data class PlantingSeasonNotificationPayload(
    val plantingSeasonId: PlantingSeasonId,
    val plantingSeasonName: String,
    val plantingSiteName: String,
    val lastEventLogId: EventLogId,
    val events: List<PlantingSeasonNotificationAlertPayload>,
) {
  constructor(
      model: PlantingSeasonNotificationModel
  ) : this(
      plantingSeasonId = model.plantingSeasonId,
      plantingSeasonName = model.plantingSeasonName,
      plantingSiteName = model.plantingSiteName,
      lastEventLogId = model.lastEventLogId,
      events = model.events.map { PlantingSeasonNotificationAlertPayload(it) },
  )
}
