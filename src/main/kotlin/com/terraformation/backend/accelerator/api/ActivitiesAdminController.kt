package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ActivityStore
import com.terraformation.backend.accelerator.model.ExistingActivityModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import io.swagger.v3.oas.annotations.Operation
import jakarta.ws.rs.QueryParam
import java.time.Instant
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/activities/admin")
@RestController
class ActivitiesAdminController(
    private val activityStore: ActivityStore,
) {
  @GetMapping
  @Operation(summary = "Lists all of a project's activities with accelerator-admin-only details.")
  @RequireGlobalRole(
      [GlobalRole.ReadOnly, GlobalRole.TFExpert, GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin]
  )
  fun adminListActivities(
      @QueryParam("projectId") projectId: ProjectId
  ): AdminListActivitiesResponsePayload {
    val activities = activityStore.fetchByProjectId(projectId)

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
    val activity = activityStore.fetchOneById(id)

    return AdminGetActivityResponsePayload(AdminActivityPayload(activity))
  }

  @Operation(summary = "Updates an activity including its accelerator-admin-only details.")
  @PutMapping("/{id}")
  @RequireGlobalRole([GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
  fun adminUpdateActivity(
      @PathVariable("id") id: ActivityId,
      @RequestBody payload: AdminUpdateActivityRequestPayload,
  ): SimpleSuccessResponsePayload {
    activityStore.update(id, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }
}

data class AdminActivityPayload(
    val createdBy: UserId,
    val createdTime: Instant,
    val date: LocalDate,
    val description: String?,
    val id: ActivityId,
    val isHighlight: Boolean,
    val isVerified: Boolean,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
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
      isVerified = model.verifiedBy != null,
      modifiedBy = model.modifiedBy,
      modifiedTime = model.modifiedTime,
      type = model.activityType,
      verifiedBy = model.verifiedBy,
      verifiedTime = model.verifiedTime,
  )
}

data class AdminUpdateActivityRequestPayload(
    val date: LocalDate,
    val description: String?,
    val isHighlight: Boolean,
    val isVerified: Boolean,
    val type: ActivityType,
) {
  fun applyTo(model: ExistingActivityModel): ExistingActivityModel {
    return model.copy(
        activityDate = date,
        activityType = type,
        description = description,
        isHighlight = isHighlight,
        isVerified = isVerified,
    )
  }
}

data class AdminGetActivityResponsePayload(val activity: AdminActivityPayload) :
    SuccessResponsePayload

data class AdminListActivitiesResponsePayload(val activities: List<AdminActivityPayload>) :
    SuccessResponsePayload
