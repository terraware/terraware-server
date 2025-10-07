package com.terraformation.backend.file

import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.default_schema.tables.pojos.ThumbnailsRow
import com.terraformation.backend.db.default_schema.tables.references.THUMBNAILS
import com.terraformation.backend.log.debugWithTiming
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.ImageUtils
import jakarta.inject.Named
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Clock
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.nameWithoutExtension
import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.resizers.configurations.ScalingMode
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.http.MediaType

@Named
class ThumbnailStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val fileStore: FileStore,
    private val filesDao: FilesDao,
    private val thumbnailsDao: ThumbnailsDao,
    private val imageUtils: ImageUtils,
) {
  /**
   * If an image is scaled to this size or larger (on either axis), use a quality-optimized scaling
   * algorithm to reduce visible scaling artifacts. Smaller target sizes can use a speed-optimized
   * algorithm.
   *
   * The design assumption here is that we're likely to be asked to generate a bunch of tiny
   * thumbnails all at once, e.g., the first time someone does a search for a certain set of
   * entities. So we want to generate those thumbnails pretty quickly, and users won't care if they
   * have pixel-perfect antialiasing and such. Larger images are likely to be requested one at a
   * time, so it's both fine to spend longer generating them and more important that they look good.
   *
   * With some image scaling algorithms, it is slower to scale to a tiny size than a medium size, so
   * setting this to a very low value may chew extra CPU cycles for no benefit.
   */
  val minSizeForHighQuality = 320

  /** Don't allow scaling to widths or heights larger than this. */
  private val maxAllowedSize = 10000

  private val log = perClassLogger()

  /**
   * Limits the number of thumbnails we can generate concurrently per server instance. Generating a
   * thumbnail eats a lot of memory, and we can run the server out of heap space if we try to
   * generate too many of them at once.
   */
  private val semaphore = Semaphore(2)

  /**
   * How long to wait before giving up trying to generate a thumbnail and returning an error to the
   * client.
   */
  private val thumbnailTimeoutSecs: Long = 60

  /** Which image MIME types we can scale natively. */
  private val supportedMimeTypes = ImageIO.getReaderMIMETypes().toSet()

  /**
   * Returns the contents of a thumbnail image for a photo. This may return a cached copy of a
   * thumbnail if one exists, or it may scale the original image down on demand.
   *
   * At least one of [maxWidth] and [maxHeight] must be non-null. If both of them are non-null, the
   * resulting thumbnail may be smaller than the limit depending on the aspect ratio of the original
   * photo.
   *
   * @param maxWidth Maximum width of thumbnail in pixels. If null, the width will be computed based
   *   on [maxHeight].
   * @param maxHeight Maximum height of the thumbnail in pixels. If null, the height will be
   *   computed based on [maxWidth].
   */
  fun getThumbnailData(fileId: FileId, maxWidth: Int?, maxHeight: Int?): SizedInputStream {
    val existing = getExistingThumbnailData(fileId, maxWidth, maxHeight)
    if (existing != null) {
      return existing
    }

    val filesRow = filesDao.fetchOneById(fileId) ?: throw FileNotFoundException(fileId)
    val photoUrl = filesRow.storageUrl!!

    return generateThumbnail(fileId, maxWidth, maxHeight, photoUrl)
  }

  /**
   * Returns the contents of an existing thumbnail image for a photo, or null if the thumbnail has
   * not been generated yet.
   *
   * The original image's aspect ratio will be preserved, such that the result is no larger than the
   * dimensions specified by [maxWidth] and [maxHeight]; it may be smaller than the requested size.
   *
   * @param maxWidth Maximum width of thumbnail in pixels. If null, the original image's width will
   *   be used.
   * @param maxHeight Maximum height of the thumbnail in pixels. If null, the original image's
   *   height will be used.
   */
  fun getExistingThumbnailData(fileId: FileId, maxWidth: Int?, maxHeight: Int?): SizedInputStream? {
    if (maxWidth != null && (maxWidth !in 1..maxAllowedSize)) {
      throw IllegalArgumentException("maxWidth is outside of allowed range 1-$maxAllowedSize")
    }

    if (maxHeight != null && (maxHeight !in 1..maxAllowedSize)) {
      throw IllegalArgumentException("maxHeight is outside of allowed range 1-$maxAllowedSize")
    }

    // After the first time a thumbnail is fetched, we can reuse the cached copy.
    val thumbnailsRow = fetchByMaximumSize(fileId, maxWidth, maxHeight)
    if (thumbnailsRow != null) {
      val thumbUrl = thumbnailsRow.storageUrl!!

      try {
        return fileStore.read(thumbUrl).withContentType(thumbnailsRow.contentType!!)
      } catch (_: NoSuchFileException) {
        log.warn("Found thumbnail $thumbUrl in database but file did not exist; regenerating it")
        thumbnailsDao.delete(thumbnailsRow)
      }
    }

    return null
  }

  /** Deletes all the thumbnails for a photo. */
  fun deleteThumbnails(fileId: FileId) {
    val thumbnails = thumbnailsDao.fetchByFileId(fileId)
    thumbnails.forEach { thumbnailsRow ->
      val storageUrl =
          thumbnailsRow.storageUrl
              ?: throw IllegalStateException("Thumbnail ${thumbnailsRow.id} had no storage URL")

      try {
        fileStore.delete(storageUrl)
      } catch (_: NoSuchFileException) {
        log.warn("Thumbnail $storageUrl was already deleted from file store")
      }

      thumbnailsDao.delete(thumbnailsRow)
    }
  }

  /**
   * Uses the largest existing thumbnail for a file to generate a new thumbnail of a particular
   * size. This can be used in cases where it's expensive to extract a thumbnail from the original
   * file; a large "thumbnail" can be extracted once, and then smaller ones can be generated
   * inexpensively as needed.
   */
  fun generateThumbnailFromExistingThumbnail(
      fileId: FileId,
      maxWidth: Int?,
      maxHeight: Int?,
  ): SizedInputStream? {
    val existingThumbnailStorageUrl =
        with(THUMBNAILS) {
          dslContext
              .select(STORAGE_URL)
              .from(THUMBNAILS)
              .where(FILE_ID.eq(fileId))
              .orderBy(WIDTH.desc(), HEIGHT.desc())
              .limit(1)
              .fetchOne(STORAGE_URL)
        } ?: return null

    return generateThumbnail(fileId, maxWidth, maxHeight, existingThumbnailStorageUrl)
  }

  /** Stores a thumbnail in the file store and records it in the database. */
  fun storeThumbnail(
      fileId: FileId,
      content: ByteArray,
      width: Int,
      height: Int,
      contentType: MediaType = MediaType.IMAGE_JPEG,
  ) {
    val filesRow = filesDao.fetchOneById(fileId) ?: throw FileNotFoundException(fileId)
    val size = content.size
    val thumbUrl = getThumbnailUrl(filesRow.storageUrl!!, width, height)
    try {
      fileStore.write(thumbUrl, ByteArrayInputStream(content))
    } catch (_: FileAlreadyExistsException) {
      // This is suspicious if it happens a lot, but we expect to see it if, e.g., two users
      // run the same search at the same time and there isn't already a thumbnail for one of
      // the results. The assumption is that that kind of race will be rare enough that it's not
      // worth trying to coordinate across servers to prevent it.
      //
      // We will still attempt to insert the database row in this case, though, to recover from
      // situations where we'd previously written the file to the file store but failed to insert
      // a row for it in the thumbnails table.
      log.warn("File $fileId thumbnail $thumbUrl already exists; keeping existing file")
    }
    val thumbnailId =
        with(THUMBNAILS) {
          dslContext
              .insertInto(THUMBNAILS)
              .set(CONTENT_TYPE, contentType.toString())
              .set(CREATED_TIME, clock.instant())
              .set(HEIGHT, height)
              .set(FILE_ID, fileId)
              .set(SIZE, size)
              .set(STORAGE_URL, thumbUrl)
              .set(WIDTH, width)
              .onConflictDoNothing()
              .returning(ID)
              .fetchOne()
              ?.id
        }
    log.info("Created file $fileId thumbnail $thumbnailId dimensions $width x $height bytes $size")
  }

  /** Returns true if we can generate thumbnails directly from a given media type. */
  fun canGenerateThumbnails(mimeType: String): Boolean {
    return mimeType.substringBefore(';') in supportedMimeTypes
  }

  private fun generateThumbnail(
      fileId: FileId,
      maxWidth: Int?,
      maxHeight: Int?,
      photoUrl: URI,
  ): SizedInputStream {
    try {
      if (!semaphore.tryAcquire(0, TimeUnit.SECONDS)) {
        log.debug("Maximum number of thumbnails already being generated; waiting for one to finish")
        if (!semaphore.tryAcquire(thumbnailTimeoutSecs, TimeUnit.SECONDS)) {
          log.error("Timed out waiting for thumbnail generation")
          throw TimeoutException("No thumbnail generation capacity is available")
        }
      }

      val resizedImage = scalePhoto(photoUrl, maxWidth, maxHeight)
      val width = resizedImage.width
      val height = resizedImage.height
      val buffer = encodeAsJpeg(resizedImage)

      storeThumbnail(fileId, buffer, width, height)

      return SizedInputStream(
          ByteArrayInputStream(buffer),
          buffer.size.toLong(),
          MediaType.IMAGE_JPEG,
      )
    } finally {
      semaphore.release()
    }
  }

  /** Compresses an image to a JPEG file in a memory buffer. */
  private fun encodeAsJpeg(image: BufferedImage): ByteArray {
    val outputStream = ByteArrayOutputStream()
    ImageIO.write(image, "JPEG", outputStream)
    return outputStream.toByteArray()
  }

  /** Scales an existing photo to a new set of maximum dimensions. */
  private fun scalePhoto(photoUrl: URI, maxWidth: Int?, maxHeight: Int?): BufferedImage {
    val originalImage =
        log.debugWithTiming("Loaded $photoUrl into image buffer") { imageUtils.read(photoUrl) }

    // Draw image onto a background that has been filled white. This ensures that transparent areas
    // are rendered white.
    val newImage =
        BufferedImage(originalImage.width, originalImage.height, BufferedImage.TYPE_INT_RGB)
    val graphics = newImage.createGraphics()
    graphics.paint = Color(255, 255, 255, 255)
    graphics.fillRect(0, 0, newImage.width, newImage.height)
    graphics.drawImage(originalImage, null, null)

    // Never scale the image larger than its original size; return the original image (with
    // white background) if the caller tries to upscale.
    val newWidth = minOf(maxWidth ?: Int.MAX_VALUE, originalImage.width)
    val newHeight = minOf(maxHeight ?: Int.MAX_VALUE, originalImage.height)

    if (newWidth >= originalImage.width && newHeight >= originalImage.height) {
      return newImage
    }

    // If the image is large enough for visual quality to matter, use a higher-quality scaling
    // algorithm.
    val useHighQuality =
        (maxWidth ?: 0) >= minSizeForHighQuality || (maxHeight ?: 0) >= minSizeForHighQuality
    val scalingMode =
        if (useHighQuality) {
          ScalingMode.PROGRESSIVE_BILINEAR
        } else {
          ScalingMode.BILINEAR
        }

    return log.debugWithTiming(
        "Resizing image from ${originalImage.width} x ${originalImage.height} to $newWidth x $newHeight"
    ) {
      Thumbnails.of(newImage)
          .imageType(BufferedImage.TYPE_INT_RGB)
          .scalingMode(scalingMode)
          .size(newWidth, newHeight)
          .asBufferedImage()
    }
  }

  /**
   * Finds an existing image for a photo that meets the requested size criteria.
   *
   * If just one of [width] or [height] is specified, finds a thumbnail whose width or height is the
   * exact value specified.
   *
   * If both [width] and [height] are specified, finds a thumbnail that is the exact width or the
   * exact height specified, and that isn't larger than the other dimension. For example, if
   * width=80 and height=60, thumbnails of these dimensions would match:
   * - 80x60 (exact match)
   * - 80x59 (exact width, height not greater than requested height)
   * - 79x60 (exact height, width not greater than requested width)
   *
   * But these wouldn't:
   * - 80x61 (exact width, but height too large)
   * - 81x60 (exact height, but width too large)
   */
  private fun fetchByMaximumSize(fileId: FileId, width: Int?, height: Int?): ThumbnailsRow? {
    val sizeCondition =
        when {
          width != null && height != null -> {
            // Return an image with either the height or the width requested, whichever one causes
            // the other dimension to not exceed the requested value.
            DSL.or(
                THUMBNAILS.HEIGHT.eq(height).and(THUMBNAILS.WIDTH.le(width)),
                THUMBNAILS.WIDTH.eq(width).and(THUMBNAILS.HEIGHT.le(height)),
            )
          }
          width != null -> THUMBNAILS.WIDTH.eq(width)
          height != null -> THUMBNAILS.HEIGHT.eq(height)
          // Return the largest available thumbnail.
          else -> null
        }

    val conditions = listOfNotNull(THUMBNAILS.FILE_ID.eq(fileId), sizeCondition)

    return dslContext
        .selectFrom(THUMBNAILS)
        .where(conditions)
        .orderBy(THUMBNAILS.WIDTH.desc(), THUMBNAILS.HEIGHT.desc())
        .limit(1)
        .fetchOneInto(ThumbnailsRow::class.java)
  }

  /**
   * Computes the URL of an image thumbnail with certain dimensions.
   *
   * This puts thumbnails in a subdirectory of the original image's directory and appends the
   * dimensions to the filename. If the original image's URL is `proto://host/a/b/c/d.jpg` and the
   * thumbnail dimensions are 640x480, the thumbnail URL will be
   * `proto://host/a/b/c/thumb/d-640x480.jpg`.
   *
   * Thumbnails are currently always JPEG images, so the file extension is always `.jpg`.
   */
  private fun getThumbnailUrl(photoUrl: URI, width: Int, height: Int): URI {
    val originalPath = Path(photoUrl.path)
    val originalFilename = originalPath.fileName
    val originalBaseName = originalFilename.nameWithoutExtension
    val thumbPath =
        originalPath.parent.resolve("thumb").resolve("$originalBaseName-${width}x$height.jpg")

    return fileStore.getUrl(thumbPath)
  }
}
