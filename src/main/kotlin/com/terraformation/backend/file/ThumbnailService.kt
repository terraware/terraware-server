package com.terraformation.backend.file

import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.file.event.FileDeletionStartedEvent
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.jooq.DSLContext
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.UnsupportedMediaTypeException

@Named
class ThumbnailService(
    private val dslContext: DSLContext,
    private val fileService: FileService,
    private val jpegConverters: List<JpegConverter>,
    private val thumbnailStore: ThumbnailStore,
) {
  private val log = perClassLogger()

  /**
   * MIME types that are acceptable to return. We'll return the original file if it is of one of
   * these types and the caller asks for its full-sized version.
   */
  private val acceptableMimeTypes = setOf(MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE)

  /**
   * Returns a thumbnail image representing an image or video file. An image with the same
   * dimensions as the original file is returned if neither [maxWidth] nor [maxHeight] is specified.
   * Otherwise, an image that is no larger than the maximum width and height is returned.
   *
   * The returned image's dimensions will never be larger than the original file's.
   */
  fun readFile(
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
      forceThumbnail: Boolean = false,
  ): SizedInputStream {
    val mimeType =
        dslContext.fetchValue(FILES.CONTENT_TYPE, FILES.ID.eq(fileId))
            ?: throw FileNotFoundException(fileId)

    // If the user is requesting a full-sized version of the image and the original's file type is
    // already acceptable, just give them the original file.
    if (
        maxWidth == null && maxHeight == null && mimeType in acceptableMimeTypes && !forceThumbnail
    ) {
      return fileService.readFile(fileId)
    }

    // For images with natively-supported formats, ThumbnailStore will handle scaling the original
    // if needed.
    if (thumbnailStore.canGenerateThumbnails(mimeType)) {
      return thumbnailStore.getThumbnailData(fileId, maxWidth, maxHeight)
    }

    // For other file types, if we've already generated a thumbnail, use it.
    val existingData = thumbnailStore.getExistingThumbnailData(fileId, maxWidth, maxHeight)
    if (existingData != null) {
      return existingData
    }

    val thumbnailFromExistingStillImage =
        thumbnailStore.generateThumbnailFromExistingThumbnail(fileId, maxWidth, maxHeight)
    if (thumbnailFromExistingStillImage != null) {
      return thumbnailFromExistingStillImage
    }

    // For video or for image formats we don't support natively, Mux or ConvertAPI will generate a
    // JPEG image, which we'll store at its original size the first time someone requests a
    // thumbnail. Then we'll scale that still image to the desired size.
    val converter =
        jpegConverters.firstOrNull { it.canConvertToJpeg(mimeType) }
            ?: throw UnsupportedMediaTypeException(
                "Cannot generate thumbnails for files of type $mimeType"
            )

    val jpegImage = converter.convertToJpeg(fileId)
    val image = ImageIO.read(ByteArrayInputStream(jpegImage))
    thumbnailStore.storeThumbnail(fileId, jpegImage, image.width, image.height)

    if (maxWidth == null && maxHeight == null) {
      // Return the full-sized JPEG we just created.
      return thumbnailStore.getExistingThumbnailData(fileId, maxWidth, maxHeight)
          ?: throw ThumbnailNotReadyException(fileId)
    }

    return thumbnailStore.generateThumbnailFromExistingThumbnail(fileId, maxWidth, maxHeight)
        ?: throw ThumbnailNotReadyException(fileId)
  }

  @EventListener
  fun on(event: FileDeletionStartedEvent) {
    try {
      thumbnailStore.deleteThumbnails(event.fileId)
    } catch (e: Exception) {
      log.error("Unable to delete thumbnails for file ${event.fileId}", e)
    }
  }
}
