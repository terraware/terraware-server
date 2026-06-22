package com.terraformation.backend.plantingmanagement.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonNotificationPage
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationGroupModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationType
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonNotificationsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@TrackingEndpoint
@RequestMapping("/api/v1/planting-seasons")
@RestController
class PlantingSeasonNotificationsController(
    private val plantingSeasonNotificationsService: PlantingSeasonNotificationsService,
) {

  @ApiResponse200
  @Operation(
      summary = "Lists all planting season notifications for the specified page.",
      description =
          "If plantingSeasonId is specified, only returns notifications for that specific Planting " +
              "Season (within the specified page).",
  )
  @GetMapping("/notifications")
  fun getPlantingSeasonNotifications(
      @RequestParam("organizationId") organizationId: OrganizationId?,
      @RequestParam("plantingSeasonId") plantingSeasonId: PlantingSeasonId?,
      @RequestParam("notificationPage") notificationPage: PlantingSeasonNotificationPage,
  ): GetPlantingSeasonNotificationsResponsePayload {
    val notifications =
        if (plantingSeasonId != null) {
          plantingSeasonNotificationsService.getNotifications(plantingSeasonId, notificationPage)
        } else if (organizationId != null) {
          plantingSeasonNotificationsService.getNotifications(organizationId, notificationPage)
        } else {
          throw IllegalArgumentException(
              "Either organizationId or plantingSeasonId must be specified."
          )
        }

    return GetPlantingSeasonNotificationsResponsePayload(
        notifications.map { PlantingSeasonNotificationGroupPayload(it) }
    )
  }
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
    @Schema(
        description =
            "The last event log id returned for the list of notifications within the specified " +
                "page. This can be used to dismiss the notifications for this page."
    )
    val lastEventLogId: EventLogId,
    val notificationPage: PlantingSeasonNotificationPage,
    val notifications: List<PlantingSeasonNotificationPayload>,
) {
  constructor(
      model: PlantingSeasonNotificationGroupModel
  ) : this(
      plantingSeasonId = model.plantingSeasonId,
      plantingSeasonName = model.plantingSeasonName,
      plantingSiteName = model.plantingSiteName,
      lastEventLogId = model.lastEventLogId,
      notificationPage = model.notificationPage,
      notifications = model.notifications.map { PlantingSeasonNotificationPayload(it) },
  )
}
