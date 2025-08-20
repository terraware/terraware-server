package com.terraformation.backend.file

import jakarta.inject.Named
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.random.Random
import org.apache.tika.mime.MimeTypes

/**
 * Generates paths for files such as photos that will be persisted in a [FileStore].
 *
 * Storage paths are of the form `yyyy/mm/dd/category/hhmmss-random.ext` where
 * - `yyyy/mm/dd` is the date part of the file's creation timestamp in UTC timezone.
 * - `category` is a short string describing what the file is for. For example, accession photos
 *   have a category of `accession`.
 * - `hhmmss` is the time part of the file's creation timestamp in UTC timezone.
 * - `random` is a 16-character-long random hexadecimal string. (See [generateBaseName] for
 *   details.)
 * - `.ext` is a file extension based on the file's MIME type, or `.bin` if the file's type doesn't
 *   have a standard file extension. For example, for JPEG files, the extension is `.jpg`.
 */
@Named
class PathGenerator(private val random: Random = Random.Default) {
  private val yearFormatter = dateTimeFormatter("yyyy")
  private val monthFormatter = dateTimeFormatter("MM")
  private val dayFormatter = dateTimeFormatter("dd")
  private val timePrefixFormatter = dateTimeFormatter("HHmmss")

  /**
   * Returns a unique path for a new file. This would typically be passed to [FileStore.getUrl].
   *
   * @param contentType The MIME type of the file. This is used to determine the file extension. If
   *   the MIME type is unknown, a default extension of `.bin` will be used.
   */
  fun generatePath(timestamp: Instant, category: String, contentType: String): Path {
    val baseName = generateBaseName(timestamp)
    val extension =
        MimeTypes.getDefaultMimeTypes().getRegisteredMimeType(contentType)?.extension ?: ".bin"

    return Path(
        yearFormatter.format(timestamp),
        monthFormatter.format(timestamp),
        dayFormatter.format(timestamp),
        category,
        "$baseName$extension",
    )
  }

  /**
   * Returns a unique string for use as the base part of the filename. The string is a 6-digit
   * timestamp (hours, minutes, seconds) followed by a random 64-bit hexadecimal value. While this
   * doesn't guarantee uniqueness, it should be good enough in practice: assuming the random number
   * generator is working as documented and producing a more or less flat distribution of values,
   * even if we generated 10,000 names per second, the
   * [collision probability](https://en.wikipedia.org/wiki/Birthday_problem#Approximations) in any
   * given second would be less than 0.0000000003%.
   */
  private fun generateBaseName(timestamp: Instant): String =
      "%s-%016X".format(timePrefixFormatter.format(timestamp), random.nextLong())

  private fun dateTimeFormatter(pattern: String) =
      DateTimeFormatter.ofPattern(pattern).withZone(ZoneOffset.UTC)!!
}
