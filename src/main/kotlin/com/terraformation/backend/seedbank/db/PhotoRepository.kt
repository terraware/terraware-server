package com.terraformation.backend.seedbank.db

import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_PHOTOS
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.log.perClassLogger
import java.io.IOException
import java.io.InputStream
import java.nio.file.NoSuchFileException
import javax.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

/** Manages storage of accession photos. */
@Named
class PhotoRepository(
    private val accessionPhotosDao: AccessionPhotosDao,
    private val dslContext: DSLContext,
    private val fileService: FileService,
) {
  private val log = perClassLogger()

  @Throws(IOException::class)
  fun storePhoto(accessionId: AccessionId, data: InputStream, size: Long, metadata: FileMetadata) {
    requirePermissions { uploadPhoto(accessionId) }

    val fileId =
        fileService.storeFile("accession", data, size, metadata) { fileId ->
          accessionPhotosDao.insert(AccessionPhotosRow(accessionId = accessionId, fileId = fileId))
        }

    log.info("Stored photo $fileId for accession $accessionId")
  }

  @Throws(IOException::class)
  fun readPhoto(
      accessionId: AccessionId,
      filename: String,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    requirePermissions { readAccession(accessionId) }

    val row = fetchFilesRow(accessionId, filename)
    return fileService.readFile(row.id!!, maxWidth, maxHeight).withContentType(row.contentType)
  }

  /** Returns a list of metadata for an accession's photos. */
  fun listPhotos(accessionId: AccessionId): List<FileMetadata> {
    requirePermissions { readAccession(accessionId) }

    return dslContext
        .select(FILES.CONTENT_TYPE, FILES.FILE_NAME, FILES.SIZE)
        .from(FILES)
        .join(ACCESSION_PHOTOS)
        .on(FILES.ID.eq(ACCESSION_PHOTOS.FILE_ID))
        .where(ACCESSION_PHOTOS.ACCESSION_ID.eq(accessionId))
        .fetch { record ->
          FileMetadata(
              contentType = record[FILES.CONTENT_TYPE]!!,
              filename = record[FILES.FILE_NAME]!!,
              size = record[FILES.SIZE]!!,
          )
        }
  }

  /** Deletes all the photos from an accession. */
  fun deleteAllPhotos(accessionId: AccessionId) {
    requirePermissions { updateAccession(accessionId) }

    dslContext
        .select(ACCESSION_PHOTOS.FILE_ID)
        .from(ACCESSION_PHOTOS)
        .join(FILES)
        .on(ACCESSION_PHOTOS.FILE_ID.eq(FILES.ID))
        .where(ACCESSION_PHOTOS.ACCESSION_ID.eq(accessionId))
        .fetch(ACCESSION_PHOTOS.FILE_ID.asNonNullable())
        .forEach { fileId ->
          fileService.deleteFile(fileId) { accessionPhotosDao.deleteById(fileId) }
        }
  }

  /** Deletes all the photos from all the accessions owned by an organization. */
  @EventListener
  fun on(event: OrganizationDeletionStartedEvent) {
    dslContext
        .selectDistinct(ACCESSION_PHOTOS.ACCESSION_ID)
        .from(ACCESSION_PHOTOS)
        .where(ACCESSION_PHOTOS.accessions.facilities.ORGANIZATION_ID.eq(event.organizationId))
        .fetch(ACCESSION_PHOTOS.ACCESSION_ID.asNonNullable())
        .forEach { deleteAllPhotos(it) }
  }

  /**
   * Returns information about an existing photo.
   *
   * @throws NoSuchFileException There was no record of the photo.
   */
  private fun fetchFilesRow(accessionId: AccessionId, filename: String): FilesRow {
    return dslContext
        .select(FILES.asterisk())
        .from(FILES)
        .join(ACCESSION_PHOTOS)
        .on(FILES.ID.eq(ACCESSION_PHOTOS.FILE_ID))
        .where(ACCESSION_PHOTOS.ACCESSION_ID.eq(accessionId))
        .and(FILES.FILE_NAME.eq(filename))
        .fetchOneInto(FilesRow::class.java)
        ?: throw NoSuchFileException(filename)
  }
}
