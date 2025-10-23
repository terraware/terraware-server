package com.terraformation.backend.accelerator

import com.drew.imaging.FileType
import com.drew.imaging.FileTypeDetector
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
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
import com.terraformation.backend.file.event.VideoFileUploadedEvent
import com.terraformation.backend.file.extractCapturedDate
import com.terraformation.backend.file.extractGeolocation
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.file.mux.MuxStreamModel
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.InputStreamCopier
import jakarta.inject.Named
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.InstantSource
import java.time.LocalDate
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.web.reactive.function.UnsupportedMediaTypeException

@Named
class ActivityMediaService(
    private val activityMediaStore: ActivityMediaStore,
    private val clock: InstantSource,
    private val eventPublisher: ApplicationEventPublisher,
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

    val copier = InputStreamCopier(data)
    val fileTypeStream = copier.getCopy()
    val exifStream = copier.getCopy()
    val storageStream = copier.getCopy()

    // Use a child thread to transfer data to the copy streams and the current thread to save the
    // file to the file store, rather than the other way around, so that the call to
    // fileService.storeFile() will use this thread's active database transaction.
    val currentThreadName = Thread.currentThread().name
    Thread.ofVirtual().name("$currentThreadName-transfer").start { copier.transfer() }

    var fileType: FileType? = null
    var exifMetadata: Metadata? = null
    var mediaType: ActivityMediaType? = null

    val exifThread =
        Thread.ofVirtual().name("$currentThreadName-exif").start {
          exifStream.use {
            try {
              // Wrap the input in a BufferedInputStream because detectFileType calls mark/reset on
              // it. But that's only done to read a small amount of header data at the beginning of
              // the file to determine the file type. (That's also why we can safely read both
              // fileTypeStream and exifStream in the same thread.) After that, we throw the
              // buffered wrapper away and close the copy stream; another copy stream is used to
              // read the actual EXIF metadata to avoid BufferedInputStream copying bytes around
              // needlessly.
              fileType =
                  fileTypeStream.use { FileTypeDetector.detectFileType(BufferedInputStream(it)) }

              exifMetadata = ImageMetadataReader.readMetadata(exifStream, -1, fileType)
            } catch (e: Exception) {
              log.error("Failed to extract EXIF data from uploaded file", e)
            }
          }
        }

    val fileId =
        storageStream.use {
          fileService.storeFile(
              "activity",
              storageStream,
              newFileMetadata,
              populateMetadata = { metadata ->
                // Make sure we've finished extracting EXIF metadata from the stream before trying
                // to pull values from it. At this point, storageStream has been completely consumed
                // because the file has been copied to the file store.
                exifThread.join()

                val capturedDate = exifMetadata?.extractCapturedDate() ?: getCurrentDate(activityId)
                metadata.copy(
                    // TODO: Extract time of day from EXIF, not just date
                    capturedLocalTime = capturedDate.atStartOfDay(),
                    geolocation = exifMetadata?.extractGeolocation(),
                )
              },
          ) { (fileId) ->
            val mimeType =
                fileType?.mimeType
                    ?: throw UnsupportedMediaTypeException("Cannot determine file type")

            mediaType =
                when {
                  mimeType.startsWith("image/") -> ActivityMediaType.Photo
                  mimeType.startsWith("video/") -> ActivityMediaType.Video
                  else ->
                      throw UnsupportedMediaTypeException(
                          "${fileType!!.longName} ($mimeType) is not a supported file type"
                      )
                }

            val row =
                ActivityMediaFilesRow(
                    activityId = activityId,
                    activityMediaTypeId = mediaType,
                    fileId = fileId,
                    isCoverPhoto = false,
                    isHiddenOnMap = false,
                    listPosition = listPosition,
                )

            activityMediaStore.insertMediaFile(row)
          }
        }

    log.info("Stored file $fileId for activity $activityId")

    if (mediaType == ActivityMediaType.Video) {
      eventPublisher.publishEvent(VideoFileUploadedEvent(fileId))
    }

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
  private fun getCurrentDate(activityId: ActivityId): LocalDate =
      LocalDate.ofInstant(clock.instant(), parentStore.getEffectiveTimeZone(activityId))
}
