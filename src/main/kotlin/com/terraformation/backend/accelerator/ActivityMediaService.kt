package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ActivityMediaStore
import com.terraformation.backend.accelerator.event.ActivityDeletionStartedEvent
import com.terraformation.backend.accelerator.model.ActivityMediaModel
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.tables.pojos.ActivityMediaFilesRow
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.file.mux.MuxStreamModel
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import java.time.InstantSource
import java.time.LocalDateTime
import org.springframework.context.event.EventListener
import org.springframework.web.reactive.function.UnsupportedMediaTypeException

@Named
class ActivityMediaService(
    private val activityMediaStore: ActivityMediaStore,
    private val clock: InstantSource,
    private val fileService: FileService,
    private val muxService: MuxService,
    private val parentStore: ParentStore,
    private val thumbnailService: ThumbnailService,
) {
  private val log = perClassLogger()

  fun storeMedia(
      activityId: ActivityId,
      data: InputStream,
      newFileMetadata: NewFileMetadata,
      listPosition: Int? = null,
  ): FileId {
    requirePermissions { updateActivity(activityId) }

    val fileId =
        fileService.storeFile(
            "activity",
            data,
            newFileMetadata,
            populateMetadata = { metadata: NewFileMetadata ->
              if (metadata.capturedLocalTime != null) {
                metadata
              } else {
                metadata.copy(capturedLocalTime = getCurrentTime(activityId))
              }
            },
        ) { storedFile ->
          val mimeType =
              storedFile.fileType?.mimeType
                  ?: throw UnsupportedMediaTypeException("Cannot determine file type")

          val mediaType =
              when {
                mimeType.startsWith("image/") -> ActivityMediaType.Photo
                mimeType.startsWith("video/") -> ActivityMediaType.Video
                else ->
                    throw UnsupportedMediaTypeException(
                        "${storedFile.fileType.longName} ($mimeType) is not a supported file type"
                    )
              }

          val row =
              ActivityMediaFilesRow(
                  activityId = activityId,
                  activityMediaTypeId = mediaType,
                  fileId = storedFile.fileId,
                  isCoverPhoto = false,
                  isHiddenOnMap = false,
                  listPosition = listPosition,
              )

          activityMediaStore.insertMediaFile(row)
        }

    log.info("Stored file $fileId for activity $activityId")

    return fileId
  }

  fun readMedia(
      activityId: ActivityId,
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
      raw: Boolean = false,
  ): SizedInputStream {
    requirePermissions { readActivity(activityId) }

    activityMediaStore.ensureFileExists(activityId, fileId)

    return if (raw) {
      fileService.readFile(fileId)
    } else {
      thumbnailService.readFile(fileId, maxWidth, maxHeight)
    }
  }

  fun getMuxStreamInfo(activityId: ActivityId, fileId: FileId): MuxStreamModel {
    requirePermissions { readActivity(activityId) }

    activityMediaStore.ensureFileExists(activityId, fileId)

    return muxService.getMuxStream(fileId)
  }

  fun deleteMedia(activityId: ActivityId, fileId: FileId) {
    requirePermissions { updateActivity(activityId) }

    activityMediaStore.deleteFromDatabase(activityId, fileId)
  }

  @EventListener
  fun on(event: ActivityDeletionStartedEvent) {
    deleteFiles(activityMediaStore.fetchByActivityId(event.activityId))
  }

  @EventListener
  fun on(event: ProjectDeletionStartedEvent) {
    deleteFiles(activityMediaStore.fetchByProjectId(event.projectId))
  }

  @EventListener
  fun on(event: OrganizationDeletionStartedEvent) {
    deleteFiles(activityMediaStore.fetchByOrganizationId(event.organizationId))
  }

  /**
   * Deletes media files from the database. This publishes events that may also cause the files to
   * be deleted from the file store.
   */
  private fun deleteFiles(mediaFiles: List<ActivityMediaModel>) {
    mediaFiles.forEach { mediaFile ->
      activityMediaStore.deleteFromDatabase(mediaFile.activityId, mediaFile.fileId)
    }
  }

  /** Returns the current date in the time zone of the organization associated with an activity. */
  private fun getCurrentTime(activityId: ActivityId): LocalDateTime =
      LocalDateTime.ofInstant(clock.instant(), parentStore.getEffectiveTimeZone(activityId))
}
