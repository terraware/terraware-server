package com.terraformation.backend.seedbank.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.PhotoId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.PhotosDao
import com.terraformation.backend.db.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.db.tables.pojos.PhotosRow
import com.terraformation.backend.db.tables.references.ACCESSION_PHOTOS
import com.terraformation.backend.db.tables.references.PHOTOS
import com.terraformation.backend.db.transformSrid
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
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
    private val accessionsDao: AccessionsDao,
    private val dslContext: DSLContext,
    private val clock: Clock,
    private val fileStore: FileStore,
    private val photosDao: PhotosDao,
    private val thumbnailStore: ThumbnailStore,
) {
  private val log = perClassLogger()

  @Throws(IOException::class)
  fun storePhoto(accessionId: AccessionId, data: InputStream, size: Long, metadata: PhotoMetadata) {
    val accession =
        accessionsDao.fetchOneById(accessionId) ?: throw AccessionNotFoundException(accessionId)

    if (!currentUser().canUpdateAccession(accessionId, accession.facilityId)) {
      throw AccessDeniedException("No permission to update accession data")
    }

    val photoUrl = fileStore.newUrl(clock.instant(), "accession", metadata.contentType)

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
  fun readPhoto(
      accessionId: AccessionId,
      filename: String,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    val accession =
        accessionsDao.fetchOneById(accessionId) ?: throw AccessionNotFoundException(accessionId)

    if (!currentUser().canReadAccession(accessionId, accession.facilityId)) {
      throw AccessionNotFoundException(accessionId)
    }

    return if (maxWidth != null || maxHeight != null) {
      thumbnailStore.getThumbnailData(fetchPhotoId(accessionId, filename), maxWidth, maxHeight)
    } else {
      fileStore.read(fetchUrl(accessionId, filename))
    }
  }

  /** Returns the photo's size in bytes. */
  @Throws(IOException::class)
  fun getPhotoFileSize(accessionId: AccessionId, filename: String): Long {
    val accession =
        accessionsDao.fetchOneById(accessionId) ?: throw AccessionNotFoundException(accessionId)

    if (!currentUser().canReadAccession(accessionId, accession.facilityId)) {
      throw AccessionNotFoundException(accessionId)
    }

    val photoUrl = fetchUrl(accessionId, filename)
    return fileStore.size(photoUrl)
  }

  /** Returns a list of metadata for an accession's photos. */
  fun listPhotos(accessionId: AccessionId): List<PhotoMetadata> {
    if (!currentUser().canReadAccession(accessionId)) {
      throw AccessionNotFoundException(accessionId)
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
  private fun fetchUrl(accessionId: AccessionId, filename: String): URI {
    return dslContext
        .select(PHOTOS.STORAGE_URL)
        .from(PHOTOS)
        .join(ACCESSION_PHOTOS)
        .on(PHOTOS.ID.eq(ACCESSION_PHOTOS.PHOTO_ID))
        .where(ACCESSION_PHOTOS.ACCESSION_ID.eq(accessionId))
        .and(PHOTOS.FILE_NAME.eq(filename))
        .fetchOne(PHOTOS.STORAGE_URL)
        ?: throw NoSuchFileException(filename)
  }

  /**
   * Returns the ID of an existing photo.
   *
   * @throws NoSuchFileException There was no record of the photo.
   */
  private fun fetchPhotoId(accessionId: AccessionId, filename: String): PhotoId {
    return dslContext
        .select(PHOTOS.ID)
        .from(PHOTOS)
        .join(ACCESSION_PHOTOS)
        .on(PHOTOS.ID.eq(ACCESSION_PHOTOS.PHOTO_ID))
        .where(ACCESSION_PHOTOS.ACCESSION_ID.eq(accessionId))
        .and(PHOTOS.FILE_NAME.eq(filename))
        .fetchOne(PHOTOS.ID)
        ?: throw NoSuchFileException(filename)
  }
}
