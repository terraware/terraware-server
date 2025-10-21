package com.terraformation.backend.accelerator

import com.drew.imaging.FileType
import com.drew.imaging.FileTypeDetector
import com.drew.imaging.ImageMetadataReader
import com.drew.lang.DateUtil
import com.drew.lang.Rational
import com.drew.metadata.Directory
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.mov.QuickTimeDirectory
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory
import com.drew.metadata.mp4.Mp4Directory
import com.terraformation.backend.accelerator.db.ActivityMediaStore
import com.terraformation.backend.accelerator.event.ActivityDeletionStartedEvent
import com.terraformation.backend.accelerator.model.ActivityMediaModel
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.tables.pojos.ActivityMediaFilesRow
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.event.VideoFileUploadedEvent
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.math.withSign
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel
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
  private val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)

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

                val capturedDate =
                    exifMetadata?.let { extractCapturedDate(it) } ?: getCurrentDate(activityId)
                metadata.copy(
                    // TODO: Extract time of day from EXIF, not just date
                    capturedLocalTime = capturedDate.atStartOfDay(),
                    geolocation = exifMetadata?.let { extractGeolocation(it) },
                )
              },
          ) { fileId ->
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

  private fun extractCapturedDate(metadata: Metadata): LocalDate? {
    return try {
      extractExifCapturedDate(metadata) ?: extractVideoCapturedDate(metadata)
    } catch (e: Exception) {
      log.warn("Failed to extract captured date from media metadata", e)
      null
    }
  }

  /** Extracts the captured date, if any, from EXIF metadata. */
  private fun extractExifCapturedDate(metadata: Metadata): LocalDate? {
    // Try the original (capture) date first, then digitization date, then modification date.
    val dateStr =
        metadata.getString<ExifSubIFDDirectory>(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
            ?: metadata.getString<ExifSubIFDDirectory>(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED)
            ?: metadata.getString<ExifSubIFDDirectory>(ExifSubIFDDirectory.TAG_DATETIME)
            ?: metadata.getString<ExifIFD0Directory>(ExifIFD0Directory.TAG_DATETIME_ORIGINAL)
            ?: metadata.getString<ExifIFD0Directory>(ExifIFD0Directory.TAG_DATETIME_DIGITIZED)
            ?: metadata.getString<ExifIFD0Directory>(ExifIFD0Directory.TAG_DATETIME)

    return if (dateStr == null) {
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
  }

  private fun extractVideoCapturedDate(metadata: Metadata): LocalDate? {
    val date =
        metadata.getDate<QuickTimeDirectory>(QuickTimeDirectory.TAG_CREATION_TIME)
            ?: metadata.getDate<Mp4Directory>(Mp4Directory.TAG_CREATION_TIME)
            ?: return null

    // In MP4 files, the date is a mandatory header field; its value is the number of seconds from
    // January 1, 1904. Treat values of 0 as missing.
    if (date == DateUtil.get1Jan1904EpochDate(0)) {
      return null
    }

    return LocalDate.ofInstant(date.toInstant(), ZoneOffset.UTC)
  }

  private fun extractGeolocation(metadata: Metadata): Point? {
    return extractExifGeolocation(metadata)
        ?: extractQuickTimeGeolocation(metadata)
        ?: extractMp4Geolocation(metadata)
  }

  /** Extracts GPS coordinates, if any, from EXIF metadata. */
  private fun extractExifGeolocation(metadata: Metadata): Point? {
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

  /**
   * Extracts GPS coordinates, if any, from QuickTime metadata. Videos from iPhones will have this.
   */
  private fun extractQuickTimeGeolocation(metadata: Metadata): Point? {
    val locationString =
        metadata.getString<QuickTimeMetadataDirectory>(
            QuickTimeMetadataDirectory.TAG_LOCATION_ISO6709
        ) ?: return null

    return parseIso6709Geolocation(locationString)
  }

  /**
   * Extracts GPS coordinates, if any, from MP4 user data. Video from Android phones will have this.
   */
  private fun extractMp4Geolocation(metadata: Metadata): Point? {
    val latitude = metadata.getDouble<Mp4Directory>(Mp4Directory.TAG_LATITUDE) ?: return null
    val longitude = metadata.getDouble<Mp4Directory>(Mp4Directory.TAG_LONGITUDE) ?: return null

    return geometryFactory.createPoint(Coordinate(longitude, latitude))
  }

  /**
   * Parses an ISO-6709 location string. These locations are a sequence of signed decimal numbers.
   * For example, `+12.34-56.7+8.9/` is a location at latitude 12.34 north, longitude 56.7 west, and
   * altitude 8.9 meters above sea level.
   */
  private fun parseIso6709Geolocation(locationString: String): Point? {
    val match = Regex("^([-+])([0-9.]+)([-+])([0-9.]+)").find(locationString)
    if (match != null) {
      val latitudeSign = if (match.groupValues[1] == "-") -1 else 1
      val latitude = match.groupValues[2].toDoubleOrNull()?.withSign(latitudeSign)
      val longitudeSign = if (match.groupValues[3] == "-") -1 else 1
      val longitude = match.groupValues[4].toDoubleOrNull()?.withSign(longitudeSign)

      if (latitude != null && longitude != null) {
        return geometryFactory.createPoint(Coordinate(longitude, latitude))
      }
    }

    log.warn("Can't parse ISO-6709 location: $locationString")
    return null
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

  /** Extracts the value of a tag from any directory of a given type that contains it. */
  private inline fun <reified T : Directory> Metadata.getDate(tagType: Int): Date? =
      getDirectoriesOfType(T::class.java).firstNotNullOfOrNull { it.getDate(tagType) }

  /** Extracts the value of a tag from any directory of a given type that contains it. */
  private inline fun <reified T : Directory> Metadata.getDouble(tagType: Int): Double? =
      getDirectoriesOfType(T::class.java).firstNotNullOfOrNull { it.getDoubleObject(tagType) }
}
