package com.terraformation.backend.file

import com.terraformation.backend.db.PhotoNotFoundException
import com.terraformation.backend.db.default_schema.PhotoId
import com.terraformation.backend.db.default_schema.tables.daos.PhotosDao
import com.terraformation.backend.db.default_schema.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.default_schema.tables.pojos.ThumbnailsRow
import com.terraformation.backend.db.default_schema.tables.references.THUMBNAILS
import com.terraformation.backend.log.debugWithTiming
import com.terraformation.backend.log.perClassLogger
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Clock
import javax.annotation.ManagedBean
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.nameWithoutExtension
import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.resizers.configurations.ScalingMode
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.http.MediaType

@ManagedBean
class ThumbnailStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val fileStore: FileStore,
    private val photosDao: PhotosDao,
    private val thumbnailsDao: ThumbnailsDao
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
  private val maxAllowedSize = 2000

  private val log = perClassLogger()

  /**
   * Returns the contents of a thumbnail image for a photo. This may return a cached copy of a
   * thumbnail if one exists, or it may scale the original image down on demand.
   *
   * At least one of [maxWidth] and [maxHeight] must be non-null. If both of them are non-null, the
   * resulting thumbnail may be smaller than the limit depending on the aspect ratio of the original
   * photo.
   *
   * @param maxWidth Maximum width of thumbnail in pixels. If null, the width will be computed based
   * on [maxHeight].
   * @param maxHeight Maximum height of the thumbnail in pixels. If null, the height will be
   * computed based on [maxWidth].
   */
  fun getThumbnailData(photoId: PhotoId, maxWidth: Int?, maxHeight: Int?): SizedInputStream {
    if (maxWidth == null && maxHeight == null) {
      throw IllegalArgumentException("At least one thumbnail dimension must be specified")
    }

    if (maxWidth != null && (maxWidth < 1 || maxWidth > maxAllowedSize)) {
      throw IllegalArgumentException("maxWidth is outside of allowed range 1-$maxAllowedSize")
    }

    if (maxHeight != null && (maxHeight < 1 || maxHeight > maxAllowedSize)) {
      throw IllegalArgumentException("maxHeight is outside of allowed range 1-$maxAllowedSize")
    }

    // After the first time a thumbnail is fetched, we can reuse the cached copy.
    val thumbnailsRow = fetchByMaximumSize(photoId, maxWidth, maxHeight)
    if (thumbnailsRow != null) {
      val thumbUrl = thumbnailsRow.storageUrl!!

      try {
        return fileStore.read(thumbUrl)
      } catch (e: NoSuchFileException) {
        log.warn("Found thumbnail $thumbUrl in database but file did not exist; regenerating it")
        thumbnailsDao.delete(thumbnailsRow)
      }
    }

    return generateThumbnail(photoId, maxWidth, maxHeight)
  }

  /** Deletes all the thumbnails for a photo. */
  fun deleteThumbnails(photoId: PhotoId) {
    val thumbnails = thumbnailsDao.fetchByPhotoId(photoId)
    thumbnails.forEach { thumbnailsRow ->
      val storageUrl =
          thumbnailsRow.storageUrl
              ?: throw IllegalStateException("Thumbnail ${thumbnailsRow.id} had no storage URL")

      try {
        fileStore.delete(storageUrl)
      } catch (e: NoSuchFileException) {
        log.warn("Thumbnail $storageUrl was already deleted from file store")
      }

      thumbnailsDao.delete(thumbnailsRow)
    }
  }

  /**
   * Generates a new thumbnail for a photo. Stores it in the file store and inserts a row in the
   * thumbnails table with its information.
   */
  private fun generateThumbnail(
      photoId: PhotoId,
      maxWidth: Int?,
      maxHeight: Int?
  ): SizedInputStream {
    val photosRow = photosDao.fetchOneById(photoId) ?: throw PhotoNotFoundException(photoId)
    val photoUrl = photosRow.storageUrl!!

    val resizedImage = scalePhoto(photoUrl, maxWidth, maxHeight)
    val buffer = encodeAsJpeg(resizedImage)
    val size = buffer.size

    val thumbUrl = getThumbnailUrl(photoUrl, resizedImage.width, resizedImage.height)

    try {
      fileStore.write(thumbUrl, ByteArrayInputStream(buffer))
    } catch (e: FileAlreadyExistsException) {
      // This is suspicious if it happens a lot, but we expect to see it if, e.g., two users
      // run the same search at the same time and there isn't already a thumbnail for one of
      // the results. The assumption is that that kind of race will be rare enough that it's not
      // worth trying to coordinate across servers to prevent it.
      //
      // We will still attempt to insert the database row in this case, though, to recover from
      // situations where we'd previously written the file to the file store but failed to insert a
      // row for it in the thumbnails table.
      log.warn("Photo $photoId thumbnail $thumbUrl already exists; keeping existing file")
    }

    val thumbnailId =
        with(THUMBNAILS) {
          dslContext
              .insertInto(THUMBNAILS)
              .set(CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
              .set(CREATED_TIME, clock.instant())
              .set(HEIGHT, resizedImage.height)
              .set(PHOTO_ID, photoId)
              .set(SIZE, size)
              .set(STORAGE_URL, thumbUrl)
              .set(WIDTH, resizedImage.width)
              .onConflictDoNothing()
              .returning(ID)
              .fetchOne()
              ?.id
        }

    log.info(
        "Created photo $photoId thumbnail $thumbnailId dimensions ${resizedImage.width} x " +
            "${resizedImage.height} bytes $size",
    )

    return SizedInputStream(ByteArrayInputStream(buffer), size.toLong())
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
        log.debugWithTiming("Loaded $photoUrl into image buffer") {
          fileStore.read(photoUrl).use { stream -> ImageIO.read(stream) }
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
        "Resizing image from ${originalImage.width} x ${originalImage.height} to $maxWidth x $maxHeight") {
          Thumbnails.of(originalImage)
              .scalingMode(scalingMode)
              .size(maxWidth ?: Int.MAX_VALUE, maxHeight ?: Int.MAX_VALUE)
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
   *
   * - 80x60 (exact match)
   * - 80x59 (exact width, height not greater than requested height)
   * - 79x60 (exact height, width not greater than requested width)
   *
   * But these wouldn't:
   *
   * - 80x61 (exact width, but height too large)
   * - 81x60 (exact height, but width too large)
   */
  private fun fetchByMaximumSize(photoId: PhotoId, width: Int?, height: Int?): ThumbnailsRow? {
    val sizeCondition =
        if (width != null) {
          if (height != null) {
            // Return an image with either the height or the width requested, whichever one causes
            // the other dimension to not exceed the requested value.
            DSL.or(
                THUMBNAILS.HEIGHT.eq(height).and(THUMBNAILS.WIDTH.le(width)),
                THUMBNAILS.WIDTH.eq(width).and(THUMBNAILS.HEIGHT.le(height)),
            )
          } else {
            THUMBNAILS.WIDTH.eq(width)
          }
        } else {
          THUMBNAILS.HEIGHT.eq(height)
        }

    return dslContext
        .selectFrom(THUMBNAILS)
        .where(THUMBNAILS.PHOTO_ID.eq(photoId))
        .and(sizeCondition)
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
