package com.terraformation.backend.util

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.terraformation.backend.file.FileStore
import jakarta.inject.Named
import java.awt.image.BufferedImage
import java.net.URI
import javax.imageio.ImageIO
import net.coobird.thumbnailator.filters.Flip
import net.coobird.thumbnailator.filters.Rotation
import org.apache.tika.Tika
import org.springframework.web.reactive.function.UnsupportedMediaTypeException

/** Utility class for image manipulation */
@Named
class ImageUtils(private val fileStore: FileStore) {

  companion object {
    const val EXIF_ORIENTATION_TAG_ID = 274
  }

  /**
   * Parse EXIF metadata and return orientation if present.
   *
   * @see https://exiv2.org/tags.html (274 decimal tag)
   * @see https://drewnoakes.com/code/exif/
   */
  fun getOrientation(photoUrl: URI): Int? {
    fileStore.read(photoUrl).use { stream ->
      val metadata = ImageMetadataReader.readMetadata(stream)
      val directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
      return directory?.getInteger(EXIF_ORIENTATION_TAG_ID)
    }
  }

  /** Utility to flip an image horizontally */
  fun flipHorizontal(image: BufferedImage): BufferedImage = Flip.HORIZONTAL.apply(image)

  /** Utility to flip an image vertically */
  fun flipVertical(image: BufferedImage): BufferedImage = Flip.VERTICAL.apply(image)

  /** Utility to rotate an image by degrees */
  fun rotateByDegree(image: BufferedImage, degree: Double): BufferedImage =
      Rotation.newRotator(degree).apply(image)

  /**
   * Rotate an image by a specified orientation. Orientations are from 1 through 8. This utility
   * leverages Thumbnailator's built-in filters to rotate and flip.
   *
   * @see https://sirv.com/help/articles/rotate-photos-to-be-upright/
   * @see
   *   https://coobird.github.io/thumbnailator/javadoc/0.4.8/net/coobird/thumbnailator/util/exif/Orientation.html
   */
  fun rotate(image: BufferedImage, orientation: Int?): BufferedImage {
    return when (orientation) {
      2 -> flipHorizontal(image)
      3 -> rotateByDegree(image, 180.0)
      4 -> flipVertical(image)
      5 -> rotateByDegree(flipHorizontal(image), 270.0)
      6 -> rotateByDegree(image, 90.0)
      7 -> rotateByDegree(flipHorizontal(image), 90.0)
      8 -> rotateByDegree(image, 270.0)
      else -> image
    }
  }

  /**
   * Utility to read an image from url into a buffered image, applying relevant metadata to the
   * buffered image.
   */
  fun read(photoUrl: URI): BufferedImage {
    val bufferedImage = fileStore.read(photoUrl).use { stream -> ImageIO.read(stream) }

    if (bufferedImage == null) {
      // ImageIO.read() returns null if the stream isn't in a supported format. Try to identify
      // what the format actually is so we can generate a useful error message.
      val detectedType = fileStore.read(photoUrl).use { stream -> Tika().detect(stream) }

      throw UnsupportedMediaTypeException(
          "Cannot read image. Detected content type is $detectedType"
      )
    }

    val orientation = getOrientation(photoUrl)

    return rotate(bufferedImage, orientation)
  }
}
