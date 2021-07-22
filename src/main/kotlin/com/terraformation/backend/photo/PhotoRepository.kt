package com.terraformation.backend.photo

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.seedbank.db.AccessionStore
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
    private val accessionPhotosDao: AccessionPhotosDao,
    private val accessionStore: AccessionStore,
    private val clock: Clock,
) {
  @Throws(IOException::class)
  fun storePhoto(
      facilityId: FacilityId,
      accessionNumber: String,
      data: InputStream,
      metadata: PhotoMetadata
  ) {
    val accessionId =
        accessionStore.getIdByNumber(facilityId, accessionNumber)
            ?: throw AccessionNotFoundException(accessionNumber)

    val photoPath = getPhotoPath(facilityId, accessionNumber, metadata.filename)
    makePhotoDir(facilityId, accessionNumber)

    try {
      val size = Files.copy(data, photoPath)

      val databaseRow =
          AccessionPhotosRow(
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

      accessionPhotosDao.insert(databaseRow)
    } catch (e: FileAlreadyExistsException) {
      // Don't delete the existing file
      throw e
    } catch (e: Exception) {
      Files.deleteIfExists(photoPath)
      throw e
    }
  }

  @Throws(IOException::class)
  fun readPhoto(facilityId: FacilityId, accessionNumber: String, filename: String): InputStream {
    val photoPath = getPhotoPath(facilityId, accessionNumber, filename)
    return Files.newInputStream(photoPath)
  }

  /** Returns the photo's size in bytes. */
  @Throws(IOException::class)
  fun getPhotoFileSize(facilityId: FacilityId, accessionNumber: String, filename: String): Long {
    val photoPath = getPhotoPath(facilityId, accessionNumber, filename)
    return Files.size(photoPath)
  }

  @Throws(IOException::class)
  private fun makePhotoDir(facilityId: FacilityId, accessionNumber: String) {
    val path = getAccessionPath(facilityId, accessionNumber)

    Files.createDirectories(path)
  }

  private fun getPhotoPath(
      facilityId: FacilityId,
      accessionNumber: String,
      filename: String
  ): Path {
    return getAccessionPath(facilityId, accessionNumber).resolve(filename)
  }

  private fun getAccessionPath(facilityId: FacilityId, accessionNumber: String): Path {
    if (accessionNumber.length < config.photoIntermediateDepth) {
      throw IllegalArgumentException("$accessionNumber is too short to be an accession number")
    }

    var path = config.photoDir.resolve(facilityId.toString())
    for (i in 0 until config.photoIntermediateDepth) {
      val element = if (i < accessionNumber.length) accessionNumber[i] else '_'
      path = path.resolve("$element")
    }

    return path.resolve(accessionNumber)
  }
}
