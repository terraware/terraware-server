package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ActivityStore
import com.terraformation.backend.accelerator.model.ExistingActivityModel
import com.terraformation.backend.accelerator.model.NewActivityModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.Operation
import jakarta.ws.rs.QueryParam
import java.time.LocalDate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/activities")
@RestController
class ActivitiesController(
    private val activityStore: ActivityStore,
) {
  @Operation(summary = "Lists all of a project's activities.")
  @GetMapping
  fun listActivities(@QueryParam("projectId") projectId: ProjectId): ListActivitiesResponsePayload {
    val activities = activityStore.fetchByProjectId(projectId)

    return ListActivitiesResponsePayload(activities.map { ActivityPayload(it) })
  }

  @Operation(summary = "Gets information about a single activity.")
  @GetMapping("/{id}")
  fun getActivity(@PathVariable("id") id: ActivityId): GetActivityResponsePayload {
    val activity = activityStore.fetchOneById(id)

    return GetActivityResponsePayload(ActivityPayload(activity))
  }

  @Operation(summary = "Creates a new activity.")
  @PostMapping
  fun createActivity(
      @RequestBody payload: CreateActivityRequestPayload
  ): GetActivityResponsePayload {
    val activity = activityStore.create(payload.toModel())

    return GetActivityResponsePayload(ActivityPayload(activity))
  }

  @DeleteMapping("/{id}")
  @Operation(
      summary = "Deletes an activity.",
      description = "Only activities that have not been published may be deleted.",
  )
  fun deleteActivity(@PathVariable("id") id: ActivityId): SimpleSuccessResponsePayload {
    activityStore.delete(id)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Updates an activity.")
  @PutMapping("/{id}")
  fun updateActivity(
      @PathVariable("id") id: ActivityId,
      @RequestBody payload: UpdateActivityRequestPayload,
  ): SimpleSuccessResponsePayload {
    activityStore.update(id, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }
}

data class ActivityPayload(
    val date: LocalDate,
    val description: String?,
    val id: ActivityId,
    val isHighlight: Boolean,
    val type: ActivityType,
) {
  constructor(
      model: ExistingActivityModel
  ) : this(
      date = model.activityDate,
      description = model.description,
      id = model.id,
      isHighlight = model.isHighlight,
      type = model.activityType,
  )
}

data class CreateActivityRequestPayload(
    val date: LocalDate,
    val description: String?,
    val projectId: ProjectId,
    val type: ActivityType,
) {
  fun toModel(): NewActivityModel {
    return NewActivityModel(
        activityDate = date,
        activityType = type,
        description = description,
        projectId = projectId,
    )
  }
}

data class UpdateActivityRequestPayload(
    val date: LocalDate,
    val description: String?,
    val type: ActivityType,
) {
  fun applyTo(model: ExistingActivityModel): ExistingActivityModel {
    return model.copy(
        activityDate = date,
        activityType = type,
        description = description,
    )
  }
}

data class GetActivityResponsePayload(val activity: ActivityPayload) : SuccessResponsePayload

data class ListActivitiesResponsePayload(val activities: List<ActivityPayload>) :
    SuccessResponsePayload
