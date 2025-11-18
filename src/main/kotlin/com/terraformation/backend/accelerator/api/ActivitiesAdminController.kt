package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ActivityStore
import com.terraformation.backend.accelerator.model.ActivityMediaModel
import com.terraformation.backend.accelerator.model.ExistingActivityModel
import com.terraformation.backend.accelerator.model.NewActivityModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.ActivityStatus
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.funder.db.PublishedActivityStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.ws.rs.QueryParam
import java.time.Instant
import java.time.LocalDate
import org.locationtech.jts.geom.Point
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/activities/admin")
@RestController
class ActivitiesAdminController(
    private val activityStore: ActivityStore,
    private val publishedActivityStore: PublishedActivityStore,
) {
  @GetMapping
  @Operation(summary = "Lists all of a project's activities with accelerator-admin-only details.")
  @RequireGlobalRole(
      [GlobalRole.ReadOnly, GlobalRole.TFExpert, GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin]
  )
  fun adminListActivities(
      @QueryParam("projectId") projectId: ProjectId,
      @Parameter(description = "If true, include a list of media files for each activity.")
      @RequestParam(defaultValue = "true")
      includeMedia: Boolean = true,
  ): AdminListActivitiesResponsePayload {
    val activities = activityStore.fetchByProjectId(projectId, includeMedia)

    return AdminListActivitiesResponsePayload(activities.map { AdminActivityPayload(it) })
  }

  @GetMapping("/{id}")
  @Operation(
      summary = "Gets information about a single activity with accelerator-admin-only details."
  )
  @RequireGlobalRole(
      [GlobalRole.ReadOnly, GlobalRole.TFExpert, GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin]
  )
  fun adminGetActivity(@PathVariable("id") id: ActivityId): AdminGetActivityResponsePayload {
    val activity = activityStore.fetchOneById(id, includeMedia = true)

    return AdminGetActivityResponsePayload(AdminActivityPayload(activity))
  }

  @Operation(summary = "Creates a new activity including accelerator-admin-only details.")
  @PostMapping
  @RequireGlobalRole([GlobalRole.TFExpert, GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
  fun adminCreateActivity(
      @RequestBody payload: AdminCreateActivityRequestPayload
  ): AdminGetActivityResponsePayload {
    val activity = activityStore.create(payload.toModel())

    return AdminGetActivityResponsePayload(AdminActivityPayload(activity))
  }

  @Operation(summary = "Updates an activity including its accelerator-admin-only details.")
  @PutMapping("/{id}")
  @RequireGlobalRole([GlobalRole.TFExpert, GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
  fun adminUpdateActivity(
      @PathVariable("id") id: ActivityId,
      @RequestBody payload: AdminUpdateActivityRequestPayload,
  ): SimpleSuccessResponsePayload {
    activityStore.update(id, true, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  @Operation(
      summary = "Publishes an activity.",
      description = "Only verified activities may be published.",
  )
  @PostMapping("/{id}/publish")
  @RequireGlobalRole([GlobalRole.TFExpert, GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
  fun adminPublishActivity(@PathVariable id: ActivityId): SimpleSuccessResponsePayload {
    publishedActivityStore.publish(id)

    return SimpleSuccessResponsePayload()
  }
}

data class AdminActivityMediaFilePayload(
    val caption: String?,
    val capturedDate: LocalDate,
    val createdBy: UserId,
    val createdTime: Instant,
    val fileId: FileId,
    val fileName: String,
    val geolocation: Point?,
    val isCoverPhoto: Boolean,
    val isHiddenOnMap: Boolean,
    val listPosition: Int,
    val type: ActivityMediaType,
) {
  constructor(
      model: ActivityMediaModel
  ) : this(
      caption = model.caption,
      capturedDate = model.capturedDate,
      createdBy = model.createdBy,
      createdTime = model.createdTime,
      fileId = model.fileId,
      fileName = model.fileName,
      geolocation = model.geolocation,
      isCoverPhoto = model.isCoverPhoto,
      isHiddenOnMap = model.isHiddenOnMap,
      listPosition = model.listPosition,
      type = model.type,
  )
}

data class AdminActivityPayload(
    val createdBy: UserId,
    val createdTime: Instant,
    val date: LocalDate,
    val description: String?,
    val id: ActivityId,
    val isHighlight: Boolean,
    val media: List<AdminActivityMediaFilePayload>,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val publishedBy: UserId?,
    val publishedTime: Instant?,
    val status: ActivityStatus,
    val type: ActivityType,
    val verifiedBy: UserId? = null,
    val verifiedTime: Instant? = null,
) {
  constructor(
      model: ExistingActivityModel
  ) : this(
      createdBy = model.createdBy,
      createdTime = model.createdTime,
      date = model.activityDate,
      description = model.description,
      id = model.id,
      isHighlight = model.isHighlight,
      media = model.media.map { AdminActivityMediaFilePayload(it) },
      modifiedBy = model.modifiedBy,
      modifiedTime = model.modifiedTime,
      publishedBy = model.publishedBy,
      publishedTime = model.publishedTime,
      status = model.activityStatus,
      type = model.activityType,
      verifiedBy = model.verifiedBy,
      verifiedTime = model.verifiedTime,
  )
}

data class AdminCreateActivityRequestPayload(
    val date: LocalDate,
    val description: String,
    val isHighlight: Boolean,
    val projectId: ProjectId,
    val status: ActivityStatus,
    val type: ActivityType,
) {
  fun toModel(): NewActivityModel {
    return NewActivityModel(
        activityDate = date,
        activityStatus = status,
        activityType = type,
        description = description,
        isHighlight = isHighlight,
        projectId = projectId,
    )
  }
}

data class AdminUpdateActivityRequestPayload(
    val date: LocalDate,
    val description: String,
    val isHighlight: Boolean,
    val status: ActivityStatus,
    val type: ActivityType,
) {
  fun applyTo(model: ExistingActivityModel): ExistingActivityModel {
    return model.copy(
        activityDate = date,
        activityStatus = status,
        activityType = type,
        description = description,
        isHighlight = isHighlight,
    )
  }
}

data class AdminGetActivityResponsePayload(val activity: AdminActivityPayload) :
    SuccessResponsePayload

data class AdminListActivitiesResponsePayload(val activities: List<AdminActivityPayload>) :
    SuccessResponsePayload
