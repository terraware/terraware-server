package com.terraformation.backend.funder.api

import com.terraformation.backend.api.FunderEndpoint
import com.terraformation.backend.api.PHOTO_MAXHEIGHT_DESCRIPTION
import com.terraformation.backend.api.PHOTO_MAXWIDTH_DESCRIPTION
import com.terraformation.backend.api.PHOTO_OPERATION_DESCRIPTION
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.file.api.GetMuxStreamResponsePayload
import com.terraformation.backend.funder.PublishedActivityService
import com.terraformation.backend.funder.db.PublishedActivityStore
import com.terraformation.backend.funder.model.PublishedActivityMediaModel
import com.terraformation.backend.funder.model.PublishedActivityModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.ws.rs.QueryParam
import java.time.LocalDate
import org.locationtech.jts.geom.Point
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController

@FunderEndpoint
@RequestMapping("/api/v1/funder/activities")
@RestController
class FunderActivitiesController(
    private val publishedActivityService: PublishedActivityService,
    private val publishedActivityStore: PublishedActivityStore,
) {
  @GetMapping
  @Operation(description = "Returns a list of published activities for a project.")
  fun funderListActivities(
      @RequestParam projectId: ProjectId,
      @RequestParam(defaultValue = "true") includeMedia: Boolean = true,
  ): FunderListActivitiesResponsePayload {
    val activities = publishedActivityStore.fetchByProjectId(projectId, includeMedia)

    return FunderListActivitiesResponsePayload(activities.map { FunderActivityPayload(it) })
  }

  @GetMapping("/{activityId}/media/{fileId}")
  @Operation(
      summary = "Gets a photo file for an activity.",
      description = PHOTO_OPERATION_DESCRIPTION,
  )
  @ResponseBody
  fun getActivityMedia(
      @PathVariable activityId: ActivityId,
      @PathVariable fileId: FileId,
      @QueryParam("maxWidth")
      @Schema(description = PHOTO_MAXWIDTH_DESCRIPTION)
      maxWidth: Int? = null,
      @QueryParam("maxHeight")
      @Schema(description = PHOTO_MAXHEIGHT_DESCRIPTION)
      maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    return publishedActivityService
        .readMedia(activityId, fileId, maxWidth, maxHeight)
        .toResponseEntity()
  }

  @GetMapping("/{activityId}/media/{fileId}/stream")
  @Operation(
      summary = "Gets streaming details for a video for an activity.",
      description =
          "This does not return the actual streaming data; it just returns the necessary " +
              "information to stream video from Mux.",
  )
  @ResponseBody
  fun getActivityMediaStream(
      @PathVariable activityId: ActivityId,
      @PathVariable fileId: FileId,
  ): GetMuxStreamResponsePayload {
    val stream = publishedActivityService.getMuxStreamInfo(activityId, fileId)

    return GetMuxStreamResponsePayload(stream)
  }
}

data class FunderActivityMediaFilePayload(
    val caption: String?,
    val capturedDate: LocalDate,
    val fileId: FileId,
    val fileName: String,
    val geolocation: Point?,
    val isCoverPhoto: Boolean,
    val isHiddenOnMap: Boolean,
    val listPosition: Int,
    val type: ActivityMediaType,
) {
  constructor(
      model: PublishedActivityMediaModel
  ) : this(
      caption = model.caption,
      capturedDate = model.capturedDate,
      fileId = model.fileId,
      fileName = model.fileName,
      geolocation = model.geolocation,
      isCoverPhoto = model.isCoverPhoto,
      isHiddenOnMap = model.isHiddenOnMap,
      listPosition = model.listPosition,
      type = model.type,
  )
}

data class FunderActivityPayload(
    val date: LocalDate,
    val description: String?,
    val id: ActivityId,
    val isHighlight: Boolean,
    val media: List<FunderActivityMediaFilePayload>,
    val type: ActivityType,
) {
  constructor(
      model: PublishedActivityModel
  ) : this(
      date = model.activityDate,
      description = model.description,
      id = model.id,
      isHighlight = model.isHighlight,
      media = model.media.map { FunderActivityMediaFilePayload(it) },
      type = model.activityType,
  )
}

data class FunderListActivitiesResponsePayload(
    val activities: List<FunderActivityPayload>,
) : SuccessResponsePayload
