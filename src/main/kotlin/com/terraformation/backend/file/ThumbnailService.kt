package com.terraformation.backend.file

import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.file.event.FileDeletionStartedEvent
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener
import org.springframework.web.reactive.function.UnsupportedMediaTypeException

@Named
class ThumbnailService(
    private val dslContext: DSLContext,
    private val fileService: FileService,
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

    throw UnsupportedMediaTypeException("Cannot generate thumbnails for files of type $mediaType")
  }
}
