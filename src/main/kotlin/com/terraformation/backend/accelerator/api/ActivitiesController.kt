package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.ActivityMediaService
import com.terraformation.backend.accelerator.db.ActivityMediaStore
import com.terraformation.backend.accelerator.db.ActivityStore
import com.terraformation.backend.accelerator.model.ActivityMediaModel
import com.terraformation.backend.accelerator.model.ExistingActivityModel
import com.terraformation.backend.accelerator.model.NewActivityModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.PHOTO_MAXHEIGHT_DESCRIPTION
import com.terraformation.backend.api.PHOTO_MAXWIDTH_DESCRIPTION
import com.terraformation.backend.api.PHOTO_OPERATION_DESCRIPTION
import com.terraformation.backend.api.RequestBodyPhotoFile
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.getFilename
import com.terraformation.backend.api.getPlainContentType
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.SUPPORTED_VIDEO_TYPES
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.file.mux.MuxStreamModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.ws.rs.QueryParam
import java.time.LocalDate
import org.locationtech.jts.geom.Point
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/activities")
@RestController
class ActivitiesController(
    private val activityMediaService: ActivityMediaService,
    private val activityMediaStore: ActivityMediaStore,
    private val activityStore: ActivityStore,
) {
  private val supportedMediaTypes = SUPPORTED_PHOTO_TYPES + SUPPORTED_VIDEO_TYPES

  @Operation(summary = "Lists all of a project's activities.")
  @GetMapping
  fun listActivities(
      @RequestParam projectId: ProjectId,
      @Parameter(description = "If true, include a list of media files for each activity.")
      @RequestParam(defaultValue = "true")
      includeMedia: Boolean = true,
  ): ListActivitiesResponsePayload {
    val activities = activityStore.fetchByProjectId(projectId, includeMedia)

    return ListActivitiesResponsePayload(activities.map { ActivityPayload(it) })
  }

  @Operation(summary = "Gets information about a single activity.")
  @GetMapping("/{activityId}")
  fun getActivity(@PathVariable activityId: ActivityId): GetActivityResponsePayload {
    val activity = activityStore.fetchOneById(activityId, includeMedia = true)

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

  @DeleteMapping("/{activityId}")
  @Operation(
      summary = "Deletes an activity.",
      description = "Only activities that have not been published may be deleted.",
  )
  fun deleteActivity(@PathVariable activityId: ActivityId): SimpleSuccessResponsePayload {
    activityStore.delete(activityId)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Updates an activity.")
  @PutMapping("/{activityId}")
  fun updateActivity(
      @PathVariable activityId: ActivityId,
      @RequestBody payload: UpdateActivityRequestPayload,
  ): SimpleSuccessResponsePayload {
    activityStore.update(activityId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Uploads media for an activity.")
  @PostMapping("/{activityId}/media", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @RequestBodyPhotoFile
  fun uploadActivityMedia(
      @PathVariable activityId: ActivityId,
      @RequestPart("listPosition", required = false) listPositionStr: String?,
      @RequestPart("file") file: MultipartFile,
  ): UploadActivityMediaResponsePayload {
    val contentType = file.getPlainContentType(supportedMediaTypes)
    val filename = file.getFilename("media")

    val fileId =
        activityMediaService.storeMedia(
            activityId,
            file.inputStream,
            FileMetadata.of(contentType, filename, file.size),
            listPositionStr?.toIntOrNull(),
        )

    return UploadActivityMediaResponsePayload(fileId)
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
    return activityMediaService
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
  ): GetActivityStreamResponsePayload {
    val stream = activityMediaService.getMuxStreamInfo(activityId, fileId)

    return GetActivityStreamResponsePayload(stream)
  }

  @Operation(summary = "Updates information about a media file for an activity.")
  @PutMapping("/{activityId}/media/{fileId}")
  fun updateActivityMedia(
      @PathVariable activityId: ActivityId,
      @PathVariable fileId: FileId,
      @RequestBody payload: UpdateActivityMediaRequestPayload,
  ): SimpleSuccessResponsePayload {
    activityMediaStore.updateMedia(activityId, fileId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  @DeleteMapping("/{activityId}/media/{fileId}")
  @Operation(summary = "Deletes a media file from an activity.")
  fun deleteActivityMedia(
      @PathVariable activityId: ActivityId,
      @PathVariable fileId: FileId,
  ): SimpleSuccessResponsePayload {
    activityMediaService.deleteMedia(activityId, fileId)

    return SimpleSuccessResponsePayload()
  }
}

data class ActivityMediaFilePayload(
    val caption: String?,
    val capturedDate: LocalDate,
    val fileId: FileId,
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
      fileId = model.fileId,
      geolocation = model.geolocation,
      isCoverPhoto = model.isCoverPhoto,
      isHiddenOnMap = model.isHiddenOnMap,
      listPosition = model.listPosition,
      type = model.type,
  )
}

data class ActivityPayload(
    val date: LocalDate,
    val description: String?,
    val id: ActivityId,
    val isHighlight: Boolean,
    val media: List<ActivityMediaFilePayload>,
    val type: ActivityType,
) {
  constructor(
      model: ExistingActivityModel
  ) : this(
      date = model.activityDate,
      description = model.description,
      id = model.id,
      isHighlight = model.isHighlight,
      media = model.media.map { ActivityMediaFilePayload(it) },
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

data class UpdateActivityMediaRequestPayload(
    val caption: String?,
    val isCoverPhoto: Boolean,
    val isHiddenOnMap: Boolean,
    val listPosition: Int,
) {
  fun applyTo(model: ActivityMediaModel): ActivityMediaModel {
    return model.copy(
        caption = caption,
        isCoverPhoto = isCoverPhoto,
        isHiddenOnMap = isHiddenOnMap,
        listPosition = listPosition,
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

data class GetActivityStreamResponsePayload(
    val playbackId: String,
    val playbackToken: String,
) : SuccessResponsePayload {
  constructor(
      model: MuxStreamModel
  ) : this(playbackId = model.playbackId, playbackToken = model.playbackToken)
}

data class ListActivitiesResponsePayload(val activities: List<ActivityPayload>) :
    SuccessResponsePayload

data class UploadActivityMediaResponsePayload(val fileId: FileId) : SuccessResponsePayload
