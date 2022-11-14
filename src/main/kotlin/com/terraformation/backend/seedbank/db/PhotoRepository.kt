package com.terraformation.backend.seedbank.db

import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.PhotoId
import com.terraformation.backend.db.default_schema.tables.references.PHOTOS
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_PHOTOS
import com.terraformation.backend.file.PhotoService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.model.PhotoMetadata
import com.terraformation.backend.log.perClassLogger
import java.io.IOException
import java.io.InputStream
import java.nio.file.NoSuchFileException
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

/** Manages storage of accession photos. */
@ManagedBean
class PhotoRepository(
    private val accessionPhotosDao: AccessionPhotosDao,
    private val dslContext: DSLContext,
    private val photoService: PhotoService,
) {
  private val log = perClassLogger()

  @Throws(IOException::class)
  fun storePhoto(accessionId: AccessionId, data: InputStream, size: Long, metadata: PhotoMetadata) {
    requirePermissions { updateAccession(accessionId) }

    val photoId =
        photoService.storePhoto("accession", data, size, metadata) { photoId ->
          accessionPhotosDao.insert(
              AccessionPhotosRow(accessionId = accessionId, photoId = photoId))
        }

    log.info("Stored photo $photoId for accession $accessionId")
  }

  @Throws(IOException::class)
  fun readPhoto(
      accessionId: AccessionId,
      filename: String,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    requirePermissions { readAccession(accessionId) }

    return photoService.readPhoto(fetchPhotoId(accessionId, filename), maxWidth, maxHeight)
  }

  /** Returns the photo's size in bytes. */
  @Throws(IOException::class)
  fun getPhotoFileSize(accessionId: AccessionId, filename: String): Long {
    requirePermissions { readAccession(accessionId) }

    return photoService.getPhotoFileSize(fetchPhotoId(accessionId, filename))
  }

  /** Returns a list of metadata for an accession's photos. */
  fun listPhotos(accessionId: AccessionId): List<PhotoMetadata> {
    requirePermissions { readAccession(accessionId) }

    return dslContext
        .select(PHOTOS.CONTENT_TYPE, PHOTOS.FILE_NAME, PHOTOS.SIZE)
        .from(PHOTOS)
        .join(ACCESSION_PHOTOS)
        .on(PHOTOS.ID.eq(ACCESSION_PHOTOS.PHOTO_ID))
        .where(ACCESSION_PHOTOS.ACCESSION_ID.eq(accessionId))
        .fetch { record ->
          PhotoMetadata(
              contentType = record[PHOTOS.CONTENT_TYPE]!!,
              filename = record[PHOTOS.FILE_NAME]!!,
              size = record[PHOTOS.SIZE]!!,
          )
        }
  }

  /** Deletes all the photos from an accession. */
  fun deleteAllPhotos(accessionId: AccessionId) {
    requirePermissions { updateAccession(accessionId) }

    dslContext
        .select(ACCESSION_PHOTOS.PHOTO_ID)
        .from(ACCESSION_PHOTOS)
        .join(PHOTOS)
        .on(ACCESSION_PHOTOS.PHOTO_ID.eq(PHOTOS.ID))
        .where(ACCESSION_PHOTOS.ACCESSION_ID.eq(accessionId))
        .fetch(ACCESSION_PHOTOS.PHOTO_ID)
        .filterNotNull()
        .forEach { photoId ->
          photoService.deletePhoto(photoId) { accessionPhotosDao.deleteById(photoId) }
        }
  }

  /** Deletes all the photos from all the accessions owned by an organization. */
  @EventListener
  fun on(event: OrganizationDeletionStartedEvent) {
    dslContext
        .selectDistinct(ACCESSION_PHOTOS.ACCESSION_ID)
        .from(ACCESSION_PHOTOS)
        .where(ACCESSION_PHOTOS.accessions.facilities.ORGANIZATION_ID.eq(event.organizationId))
        .fetch(ACCESSION_PHOTOS.ACCESSION_ID)
        .filterNotNull()
        .forEach { deleteAllPhotos(it) }
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
