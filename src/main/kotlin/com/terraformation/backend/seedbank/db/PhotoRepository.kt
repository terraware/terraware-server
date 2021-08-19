package com.terraformation.backend.seedbank.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.seedbank.model.PhotoMetadata
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock
import javax.annotation.ManagedBean
import kotlin.io.path.Path
import org.springframework.security.access.AccessDeniedException

/**
 * Manages storage of photos including metadata. In this implementation, image files are stored on
 * the filesystem and metadata in the database.
 *
 * Each accession's photos are in a subdirectory whose path includes the facility ID and the
 * accession number (not the numeric accession ID). The configuration setting
 * [TerrawareServerConfig.photoIntermediateDepth] controls the depth of that subdirectory path.
 */
@ManagedBean
class PhotoRepository(
    private val accessionPhotosDao: AccessionPhotosDao,
    private val accessionStore: AccessionStore,
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val fileStore: FileStore,
) {
  @Throws(IOException::class)
  fun storePhoto(
      facilityId: FacilityId,
      accessionNumber: String,
      data: InputStream,
      size: Long,
      metadata: PhotoMetadata
  ) {
    val accessionId =
        accessionStore.getIdByNumber(facilityId, accessionNumber)
            ?: throw AccessionNotFoundException(accessionNumber)

    if (!currentUser().canUpdateAccession(accessionId, facilityId)) {
      throw AccessDeniedException("No permission to update accession data")
    }

    val photoPath = getPhotoPath(facilityId, accessionNumber, metadata.filename)

    try {
      fileStore.write(photoPath, data, size)

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
      try {
        fileStore.delete(photoPath)
      } catch (ignore: NoSuchFileException) {
        // Swallow this; file is already deleted
      }
      throw e
    }
  }

  @Throws(IOException::class)
  fun readPhoto(facilityId: FacilityId, accessionNumber: String, filename: String): InputStream {
    val accessionId =
        accessionStore.getIdByNumber(facilityId, accessionNumber)
            ?: throw AccessionNotFoundException(accessionNumber)

    if (!currentUser().canReadAccession(accessionId, facilityId)) {
      throw AccessDeniedException("No permission to read accession data")
    }

    val photoPath = getPhotoPath(facilityId, accessionNumber, filename)
    return fileStore.read(photoPath)
  }

  /** Returns the photo's size in bytes. */
  @Throws(IOException::class)
  fun getPhotoFileSize(facilityId: FacilityId, accessionNumber: String, filename: String): Long {
    val accessionId =
        accessionStore.getIdByNumber(facilityId, accessionNumber)
            ?: throw AccessionNotFoundException(accessionNumber)

    if (!currentUser().canReadAccession(accessionId, facilityId)) {
      throw AccessDeniedException("No permission to read accession data")
    }

    val photoPath = getPhotoPath(facilityId, accessionNumber, filename)
    return fileStore.size(photoPath)
  }

  /** Returns the relative path of a photo. */
  private fun getPhotoPath(
      facilityId: FacilityId,
      accessionNumber: String,
      filename: String
  ): Path {
    return getAccessionPath(facilityId, accessionNumber).resolve(filename)
  }

  /** Returns the relative path of the directory that contains an accession's photos. */
  private fun getAccessionPath(facilityId: FacilityId, accessionNumber: String): Path {
    if (accessionNumber.length < config.photoIntermediateDepth) {
      throw IllegalArgumentException("$accessionNumber is too short to be an accession number")
    }

    var path = Path("$facilityId")
    for (i in 0 until config.photoIntermediateDepth) {
      val element = if (i < accessionNumber.length) accessionNumber[i] else '_'
      path = path.resolve("$element")
    }

    return path.resolve(accessionNumber)
  }
}
