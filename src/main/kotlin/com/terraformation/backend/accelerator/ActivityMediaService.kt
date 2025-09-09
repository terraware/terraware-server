package com.terraformation.backend.accelerator

import com.drew.imaging.FileTypeDetector
import com.drew.imaging.ImageMetadataReader
import com.drew.lang.Rational
import com.drew.metadata.Directory
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.terraformation.backend.accelerator.event.ActivityDeletionStartedEvent
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.InputStreamCopier
import jakarta.inject.Named
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.InstantSource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.jooq.Condition
import org.jooq.DSLContext
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel
import org.springframework.context.event.EventListener

@Named
class ActivityMediaService(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val fileService: FileService,
    private val parentStore: ParentStore,
) {
  private val log = perClassLogger()
  private val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)

  fun storeMedia(
      activityId: ActivityId,
      data: InputStream,
      newFileMetadata: NewFileMetadata,
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

    var exifMetadata: Metadata? = null

    val exifThread =
        Thread.ofVirtual().name("$currentThreadName-exif").start {
          exifStream.use {
            // Wrap the input in a BufferedInputStream because detectFileType calls mark/reset on
            // it. But that's only done to read a small amount of header data at the beginning of
            // the file to determine the file type. (That's also why we can safely read both
            // fileTypeStream and exifStream in the same thread.) After that, we throw the buffered
            // wrapper away and close the copy stream; another copy stream is used to read the
            // actual EXIF metadata to avoid BufferedInputStream copying bytes around needlessly.
            val fileType =
                fileTypeStream.use { FileTypeDetector.detectFileType(BufferedInputStream(it)) }

            try {
              exifMetadata = ImageMetadataReader.readMetadata(exifStream, -1, fileType)
            } catch (e: Exception) {
              log.error("Failed to extract EXIF data from uploaded file", e)
            }
          }
        }

    val fileId =
        storageStream.use {
          fileService.storeFile("activity", storageStream, newFileMetadata) { fileId ->
            // Make sure we've finished extracting EXIF metadata from the stream before trying
            // to pull values from it. At this point, storageStream has been completely consumed
            // because the file has been copied to the file store.
            exifThread.join()

            val capturedDate =
                exifMetadata?.let { extractCapturedDate(it) } ?: getCurrentDate(activityId)
            val geolocation = exifMetadata?.let { extractGeolocation(it) }

            dslContext
                .insertInto(ACTIVITY_MEDIA_FILES)
                .set(ACTIVITY_MEDIA_FILES.FILE_ID, fileId)
                .set(ACTIVITY_MEDIA_FILES.ACTIVITY_ID, activityId)
                .set(ACTIVITY_MEDIA_FILES.ACTIVITY_MEDIA_TYPE_ID, ActivityMediaType.Photo)
                .set(ACTIVITY_MEDIA_FILES.IS_COVER_PHOTO, false)
                .set(ACTIVITY_MEDIA_FILES.CAPTURED_DATE, capturedDate)
                .set(ACTIVITY_MEDIA_FILES.GEOLOCATION, geolocation)
                .execute()
          }
        }

    log.info("Stored file $fileId for activity $activityId")

    return fileId
  }

  fun readMedia(
      activityId: ActivityId,
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    requirePermissions { readActivity(activityId) }

    checkFileExists(activityId, fileId)

    return fileService.readFile(fileId, maxWidth, maxHeight)
  }

  fun deleteMedia(activityId: ActivityId, fileId: FileId) {
    requirePermissions { updateActivity(activityId) }

    checkFileExists(activityId, fileId)

    fileService.deleteFile(fileId) {
      dslContext
          .deleteFrom(ACTIVITY_MEDIA_FILES)
          .where(ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(activityId))
          .and(ACTIVITY_MEDIA_FILES.FILE_ID.eq(fileId))
          .execute()
    }
  }

  @EventListener
  fun on(event: ActivityDeletionStartedEvent) {
    deleteByCondition(ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(event.activityId))
  }

  @EventListener
  fun on(event: OrganizationDeletionStartedEvent) {
    deleteByCondition(
        ACTIVITY_MEDIA_FILES.activities.projects.ORGANIZATION_ID.eq(event.organizationId)
    )
  }

  private fun deleteByCondition(condition: Condition) {
    dslContext
        .select(ACTIVITY_MEDIA_FILES.FILE_ID)
        .from(ACTIVITY_MEDIA_FILES)
        .where(condition)
        .fetch(ACTIVITY_MEDIA_FILES.FILE_ID)
        .filterNotNull()
        .forEach { fileId ->
          fileService.deleteFile(fileId) {
            dslContext
                .deleteFrom(ACTIVITY_MEDIA_FILES)
                .where(ACTIVITY_MEDIA_FILES.FILE_ID.eq(fileId))
                .execute()
          }
        }
  }

  private fun checkFileExists(activityId: ActivityId, fileId: FileId) {
    val fileExists =
        dslContext.fetchExists(
            ACTIVITY_MEDIA_FILES,
            ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(activityId),
            ACTIVITY_MEDIA_FILES.FILE_ID.eq(fileId),
        )
    if (!fileExists) {
      throw FileNotFoundException(fileId)
    }
  }

  /** Extracts the captured date, if any, from EXIF metadata. */
  private fun extractCapturedDate(metadata: Metadata): LocalDate? {
    return try {
      // Try the original (capture) date first, then digitization date, then modification date.
      val dateStr =
          metadata.getString<ExifSubIFDDirectory>(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
              ?: metadata.getString<ExifSubIFDDirectory>(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED)
              ?: metadata.getString<ExifSubIFDDirectory>(ExifSubIFDDirectory.TAG_DATETIME)
              ?: metadata.getString<ExifIFD0Directory>(ExifIFD0Directory.TAG_DATETIME_ORIGINAL)
              ?: metadata.getString<ExifIFD0Directory>(ExifIFD0Directory.TAG_DATETIME_DIGITIZED)
              ?: metadata.getString<ExifIFD0Directory>(ExifIFD0Directory.TAG_DATETIME)

      if (dateStr == null) {
        log.debug("No date tags found in file")
        null
      } else if (dateStr.length < 8) {
        log.warn("EXIF date string is too short: $dateStr")
        null
      } else {
        // EXIF date/time strings always start with a 4-digit year, but after that there can be a
        // variety of separators (2025:11:22, 2025-11-22, 2025.11-22) or no separator (20251122).
        // And there can be a time of day after the date, or not.
        val separator = if (dateStr[4].isDigit()) "" else dateStr.substring(4, 5)
        val formatter = DateTimeFormatter.ofPattern("yyyy${separator}MM${separator}dd")
        LocalDate.parse(dateStr.substring(0, 8 + separator.length * 2), formatter)
      }
    } catch (e: Exception) {
      log.warn("Failed to extract captured date from media metadata", e)
      null
    }
  }

  /** Extracts GPS coordinates, if any, from EXIF metadata. */
  private fun extractGeolocation(metadata: Metadata): Point? {
    val gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory::class.java) ?: return null
    val latitudeRef = gpsDirectory.getString(GpsDirectory.TAG_LATITUDE_REF) ?: return null
    val longitudeRef = gpsDirectory.getString(GpsDirectory.TAG_LONGITUDE_REF) ?: return null
    val latitudeData = gpsDirectory.getRationalArray(GpsDirectory.TAG_LATITUDE) ?: return null
    val longitudeData = gpsDirectory.getRationalArray(GpsDirectory.TAG_LONGITUDE) ?: return null

    if (latitudeData.size != 3 || longitudeData.size != 3) {
      log.warn("Geolocation data in unexpected format: lat=$latitudeData long=$longitudeData")
      return null
    }

    val latitude = dmsToDouble(latitudeData, latitudeRef == "S")
    val longitude = dmsToDouble(longitudeData, longitudeRef == "W")

    if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
      log.warn("Invalid GPS coordinates found: lat=$latitude, lon=$longitude")
      return null
    }

    return geometryFactory.createPoint(Coordinate(longitude, latitude))
  }

  /** Converts GPS coordinates from degrees/minutes/seconds format to decimal. */
  private fun dmsToDouble(dms: Array<Rational>, isNegative: Boolean): Double {
    val degrees = dms[0].toDouble()
    val minutes = dms[1].toDouble()
    val seconds = dms[2].toDouble()

    val decimal = degrees + (minutes / 60.0) + (seconds / 3600.0)
    return if (isNegative) -decimal else decimal
  }

  /** Returns the current date in the time zone of the organization associated with an activity. */
  private fun getCurrentDate(activityId: ActivityId): LocalDate =
      LocalDate.ofInstant(clock.instant(), parentStore.getEffectiveTimeZone(activityId))

  /** Extracts the value of a tag from any directory of a given type that contains it. */
  private inline fun <reified T : Directory> Metadata.getString(tagType: Int): String? =
      getDirectoriesOfType(T::class.java).firstNotNullOfOrNull { it.getString(tagType) }
}
