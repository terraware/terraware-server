package com.terraformation.seedbank.photo

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.AccessionNotFoundException
import com.terraformation.seedbank.db.AccessionStore
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
    private val accessionStore: AccessionStore,
    private val clock: Clock,
) {
  @Throws(IOException::class)
  fun storePhoto(accessionNumber: String, data: InputStream, metadata: PhotoMetadataFields) {
    val accessionId =
        accessionStore.getIdByNumber(accessionNumber)
            ?: throw AccessionNotFoundException(accessionNumber)

    val photoPath = getPhotoPath(accessionNumber, metadata.filename)
    makePhotoDir(accessionNumber)

    try {
      val size = Files.copy(data, photoPath)

      val databaseRow =
          AccessionPhoto(
              accessionId = accessionId,
              capturedTime = metadata.capturedTime,
              contentType = metadata.contentType,
              filename = metadata.filename,
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
