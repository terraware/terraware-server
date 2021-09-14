package com.terraformation.backend.seedbank.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.tables.daos.PhotosDao
import com.terraformation.backend.db.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.db.tables.pojos.PhotosRow
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_PHOTOS
import com.terraformation.backend.db.tables.references.PHOTOS
import com.terraformation.backend.db.transformSrid
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.PathGenerator
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.model.PhotoMetadata
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
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
    private val dslContext: DSLContext,
    private val clock: Clock,
    private val fileStore: FileStore,
    private val pathGenerator: PathGenerator,
    private val photosDao: PhotosDao,
) {
  private val log = perClassLogger()

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

    val path = pathGenerator.generatePath(clock.instant(), "accession", metadata.contentType)
    val photoUrl = fileStore.getUrl(path)

    try {
      fileStore.write(photoUrl, data, size)

      dslContext.transaction { _ ->
        val photosRow =
            PhotosRow(
                capturedTime = metadata.capturedTime,
                contentType = metadata.contentType,
                createdTime = clock.instant(),
                fileName = metadata.filename,
                gpsHorizAccuracy = metadata.gpsAccuracy?.toDouble(),
                location = metadata.location,
                modifiedTime = clock.instant(),
                size = size,
                storageUrl = photoUrl,
            )

        photosDao.insert(photosRow)

        val accessionPhotosRow =
            AccessionPhotosRow(
                accessionId = accessionId,
                photoId = photosRow.id,
            )

        accessionPhotosDao.insert(accessionPhotosRow)
      }

      log.info("Stored $photoUrl for accession $accessionId")
    } catch (e: FileAlreadyExistsException) {
      // Don't delete the existing file
      throw e
    } catch (e: Exception) {
      try {
        fileStore.delete(photoUrl)
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

    val photoUrl = fetchUrl(facilityId, accessionNumber, filename)
    return fileStore.read(photoUrl)
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

    val photoUrl = fetchUrl(facilityId, accessionNumber, filename)
    return fileStore.size(photoUrl)
  }

  /** Returns a list of metadata for an accession's photos. */
  fun listPhotos(facilityId: FacilityId, accessionNumber: String): List<PhotoMetadata> {
    val accessionId =
        accessionStore.getIdByNumber(facilityId, accessionNumber)
            ?: throw AccessionNotFoundException(accessionNumber)

    if (!currentUser().canReadAccession(accessionId, facilityId)) {
      throw AccessDeniedException("No permission to read accession data")
    }

    return dslContext
        .select(
            PHOTOS.CAPTURED_TIME,
            PHOTOS.CONTENT_TYPE,
            PHOTOS.SIZE,
            PHOTOS.GPS_HORIZ_ACCURACY,
            PHOTOS.LOCATION.transformSrid(SRID.LONG_LAT),
            PHOTOS.FILE_NAME)
        .from(PHOTOS)
        .join(ACCESSION_PHOTOS)
        .on(PHOTOS.ID.eq(ACCESSION_PHOTOS.PHOTO_ID))
        .where(ACCESSION_PHOTOS.ACCESSION_ID.eq(accessionId))
        .fetch { record ->
          PhotoMetadata(
              record[PHOTOS.FILE_NAME]!!,
              record[PHOTOS.CONTENT_TYPE]!!,
              record[PHOTOS.CAPTURED_TIME]!!,
              record[PHOTOS.SIZE]!!,
              record[PHOTOS.LOCATION]?.firstPoint,
              record[PHOTOS.GPS_HORIZ_ACCURACY]?.toInt())
        }
  }

  /**
   * Returns the storage URL of an existing photo.
   *
   * @throws NoSuchFileException There was no record of the photo.
   */
  private fun fetchUrl(facilityId: FacilityId, accessionNumber: String, filename: String): URI {
    return dslContext
        .select(PHOTOS.STORAGE_URL)
        .from(PHOTOS)
        .join(ACCESSION_PHOTOS)
        .on(PHOTOS.ID.eq(ACCESSION_PHOTOS.PHOTO_ID))
        .join(ACCESSIONS)
        .on(ACCESSIONS.ID.eq(ACCESSION_PHOTOS.ACCESSION_ID))
        .where(ACCESSIONS.FACILITY_ID.eq(facilityId))
        .and(ACCESSIONS.NUMBER.eq(accessionNumber))
        .and(PHOTOS.FILE_NAME.eq(filename))
        .fetchOne(PHOTOS.STORAGE_URL)
        ?: throw NoSuchFileException(filename)
  }
}
