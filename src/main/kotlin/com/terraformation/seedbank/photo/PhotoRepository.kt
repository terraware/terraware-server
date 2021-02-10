package com.terraformation.seedbank.photo

import com.terraformation.seedbank.api.seedbank.ListPhotosResponseElement
import com.terraformation.seedbank.api.seedbank.UploadPhotoMetadataPayload
import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.tables.daos.AccessionPhotoDao
import com.terraformation.seedbank.db.tables.pojos.AccessionPhoto
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import javax.annotation.ManagedBean

/**
 * Manages storage of photos including metadata. In this implementation, image files are stored on
 * the filesystem and metadata in the database.
 *
 * Each accession's photos are in a subdirectory whose name is the accession number (not the numeric
 * accession ID). The configuration settings [TerrawareServerConfig.photoDir] and
 * [TerrawareServerConfig.photoIntermediateDepth] control where that subdirectory lives.
 */
@ManagedBean
class PhotoRepository(
    private val config: TerrawareServerConfig,
    private val accessionPhotoDao: AccessionPhotoDao,
    private val clock: Clock,
) {
  @Throws(IOException::class)
  fun storePhoto(
      accessionId: Long,
      accessionNumber: String,
      filename: String,
      contentType: String,
      data: InputStream,
      metadata: UploadPhotoMetadataPayload
  ) {
    val photoPath = getPhotoPath(accessionNumber, filename)
    makePhotoDir(accessionNumber)

    try {
      val size = Files.copy(data, photoPath)

      val databaseRow =
          AccessionPhoto(
              accessionId = accessionId,
              capturedTime = metadata.capturedTime,
              contentType = contentType,
              filename = filename,
              gpsAccuracy = metadata.gpsAccuracy,
              latitude = metadata.latitude,
              longitude = metadata.longitude,
              size = size.toInt(),
              uploadedTime = clock.instant(),
          )

      accessionPhotoDao.insert(databaseRow)
    } catch (e: FileAlreadyExistsException) {
      // Don't delete the existing file
      throw e
    } catch (e: Exception) {
      Files.deleteIfExists(photoPath)
      throw e
    }
  }

  @Throws(IOException::class)
  fun readPhoto(accessionNumber: String, filename: String): InputStream {
    val photoPath = getPhotoPath(accessionNumber, filename)
    return Files.newInputStream(photoPath)
  }

  /** Returns the photo's size in bytes. */
  @Throws(IOException::class)
  fun getPhotoFileSize(accessionNumber: String, filename: String): Long {
    val photoPath = getPhotoPath(accessionNumber, filename)
    return Files.size(photoPath)
  }

  fun listPhotos(accessionId: Long): List<ListPhotosResponseElement> {
    return accessionPhotoDao.fetchByAccessionId(accessionId).map {
      ListPhotosResponseElement(
          filename = it.filename!!,
          capturedTime = it.capturedTime!!,
          gpsAccuracy = it.gpsAccuracy,
          latitude = it.latitude,
          longitude = it.longitude,
          size = it.size!!,
      )
    }
  }

  @Throws(IOException::class)
  private fun makePhotoDir(accessionNumber: String) {
    val path = getAccessionPath(accessionNumber)

    Files.createDirectories(path)
  }

  private fun getPhotoPath(accessionNumber: String, filename: String): Path {
    return getAccessionPath(accessionNumber).resolve(filename)
  }

  private fun getAccessionPath(accessionNumber: String): Path {
    if (accessionNumber.length < config.photoIntermediateDepth) {
      throw IllegalArgumentException("$accessionNumber is too short to be an accession number")
    }

    var path = config.photoDir
    for (i in 0 until config.photoIntermediateDepth) {
      val element = if (i < accessionNumber.length) accessionNumber[i] else '_'
      path = path.resolve("$element")
    }

    return path.resolve(accessionNumber)
  }
}
