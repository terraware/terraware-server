package com.terraformation.backend.file

import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.file.event.FileDeletionStartedEvent
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.jooq.DSLContext
import org.springframework.context.event.EventListener
import org.springframework.web.reactive.function.UnsupportedMediaTypeException

@Named
class ThumbnailService(
    private val dslContext: DSLContext,
    private val fileService: FileService,
    private val muxService: MuxService,
    private val thumbnailStore: ThumbnailStore,
) {
  private val log = perClassLogger()

  /**
   * Reads a file or a thumbnail image representing the file. The original file is read if neither
   * [maxWidth] nor [maxHeight] is specified; otherwise an image that is no larger than the maximum
   * width and height is returned.
   */
  fun readFile(fileId: FileId, maxWidth: Int? = null, maxHeight: Int? = null): SizedInputStream {
    return if (maxWidth == null && maxHeight == null) {
      fileService.readFile(fileId)
    } else {
      getThumbnailData(fileId, maxWidth, maxHeight)
    }
  }

  @EventListener
  fun on(event: FileDeletionStartedEvent) {
    try {
      thumbnailStore.deleteThumbnails(event.fileId)
    } catch (e: Exception) {
      log.error("Unable to delete thumbnails for file ${event.fileId}", e)
    }
  }

  private fun getThumbnailData(fileId: FileId, maxWidth: Int?, maxHeight: Int?): SizedInputStream {
    val mediaType =
        dslContext.fetchValue(FILES.CONTENT_TYPE, FILES.ID.eq(fileId))
            ?: throw FileNotFoundException(fileId)

    // For images, ThumbnailStore will handle scaling the original if needed.
    if (mediaType.startsWith("image/")) {
      return thumbnailStore.getThumbnailData(fileId, maxWidth, maxHeight)
    }

    // For other file types, if we've already generated a thumbnail, use it.
    val existingData = thumbnailStore.getExistingThumbnailData(fileId, maxWidth, maxHeight)
    if (existingData != null) {
      return existingData
    }

    // For video, Mux will generate a still image, which we'll store at its original size the first
    // time someone requests a thumbnail. Then we'll scale that still image to the desired size. We
    // do the scaling ourselves because Mux limits the number of thumbnails per video.
    if (mediaType.startsWith("video/")) {
      val thumbnailFromExistingStillImage =
          thumbnailStore.generateThumbnailFromExistingThumbnail(fileId, maxWidth, maxHeight)
      if (thumbnailFromExistingStillImage != null) {
        return thumbnailFromExistingStillImage
      }

      val thumbnailData = muxService.getStillJpegImage(fileId)
      val image = ImageIO.read(ByteArrayInputStream(thumbnailData))
      thumbnailStore.storeThumbnail(fileId, thumbnailData, image.width, image.height)

      return thumbnailStore.generateThumbnailFromExistingThumbnail(fileId, maxWidth, maxHeight)
          ?: throw ThumbnailNotReadyException(fileId)
    }

    throw UnsupportedMediaTypeException("Cannot generate thumbnails for files of type $mediaType")
  }
}
