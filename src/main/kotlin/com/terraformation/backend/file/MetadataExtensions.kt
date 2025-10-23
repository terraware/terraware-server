package com.terraformation.backend.file

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
import com.terraformation.backend.db.SRID
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.math.withSign
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Metadata::class.java)
private val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)

/** Returns the date the file was originally captured, if available. */
fun Metadata.extractCapturedDate(): LocalDate? {
  return try {
    extractExifCapturedDate() ?: extractVideoCapturedDate()
  } catch (e: Exception) {
    log.warn("Failed to extract captured date from media metadata", e)
    null
  }
}

/** Returns the geolocation (GPS) coordinates from a file's metadata, if any. */
fun Metadata.extractGeolocation(): Point? {
  return extractExifGeolocation() ?: extractQuickTimeGeolocation() ?: extractMp4Geolocation()
}

/** Extracts the captured date, if any, from EXIF metadata. */
@Suppress("ReplaceSubstringWithIndexingOperation") // Hide bogus IntelliJ suggestion
private fun Metadata.extractExifCapturedDate(): LocalDate? {
  // Try the original (capture) date first, then digitization date, then modification date.
  val dateStr =
      getString<ExifSubIFDDirectory>(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
          ?: getString<ExifSubIFDDirectory>(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED)
          ?: getString<ExifSubIFDDirectory>(ExifSubIFDDirectory.TAG_DATETIME)
          ?: getString<ExifIFD0Directory>(ExifIFD0Directory.TAG_DATETIME_ORIGINAL)
          ?: getString<ExifIFD0Directory>(ExifIFD0Directory.TAG_DATETIME_DIGITIZED)
          ?: getString<ExifIFD0Directory>(ExifIFD0Directory.TAG_DATETIME)

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
    LocalDate.parse(dateStr.take(8 + separator.length * 2), formatter)
  }
}

private fun Metadata.extractVideoCapturedDate(): LocalDate? {
  val date =
      getDate<QuickTimeDirectory>(QuickTimeDirectory.TAG_CREATION_TIME)
          ?: getDate<Mp4Directory>(Mp4Directory.TAG_CREATION_TIME)
          ?: return null

  // In MP4 files, the date is a mandatory header field; its value is the number of seconds from
  // January 1, 1904. Treat values of 0 as missing.
  if (date == DateUtil.get1Jan1904EpochDate(0)) {
    return null
  }

  return LocalDate.ofInstant(date.toInstant(), ZoneOffset.UTC)
}

/** Extracts GPS coordinates, if any, from EXIF metadata. */
private fun Metadata.extractExifGeolocation(): Point? {
  val gpsDirectory = getFirstDirectoryOfType(GpsDirectory::class.java) ?: return null
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
private fun Metadata.extractQuickTimeGeolocation(): Point? {
  val locationString =
      getString<QuickTimeMetadataDirectory>(QuickTimeMetadataDirectory.TAG_LOCATION_ISO6709)
          ?: return null

  return parseIso6709Geolocation(locationString)
}

/**
 * Extracts GPS coordinates, if any, from MP4 user data. Video from Android phones will have this.
 */
private fun Metadata.extractMp4Geolocation(): Point? {
  val latitude = getDouble<Mp4Directory>(Mp4Directory.TAG_LATITUDE) ?: return null
  val longitude = getDouble<Mp4Directory>(Mp4Directory.TAG_LONGITUDE) ?: return null

  return geometryFactory.createPoint(Coordinate(longitude, latitude))
}

/**
 * Parses an ISO-6709 location string. These locations are a sequence of signed decimal numbers. For
 * example, `+12.34-56.7+8.9/` is a location at latitude 12.34 north, longitude 56.7 west, and
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

/** Extracts the value of a tag from any directory of a given type that contains it. */
private inline fun <reified T : Directory> Metadata.getString(tagType: Int): String? =
    getDirectoriesOfType(T::class.java).firstNotNullOfOrNull { it.getString(tagType) }

/** Extracts the value of a tag from any directory of a given type that contains it. */
private inline fun <reified T : Directory> Metadata.getDate(tagType: Int): Date? =
    getDirectoriesOfType(T::class.java).firstNotNullOfOrNull { it.getDate(tagType) }

/** Extracts the value of a tag from any directory of a given type that contains it. */
private inline fun <reified T : Directory> Metadata.getDouble(tagType: Int): Double? =
    getDirectoriesOfType(T::class.java).firstNotNullOfOrNull { it.getDoubleObject(tagType) }
