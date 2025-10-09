package com.terraformation.backend.seedbank.db

import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_PHOTOS
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.model.ExistingFileMetadata
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.ImageUtils
import jakarta.inject.Named
import java.io.IOException
import java.io.InputStream
import java.nio.file.NoSuchFileException
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

/** Manages storage of accession photos. */
@Named
class PhotoRepository(
    private val accessionPhotosDao: AccessionPhotosDao,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val fileService: FileService,
    private val imageUtils: ImageUtils,
    private val thumbnailService: ThumbnailService,
) {
  private val log = perClassLogger()

  @Throws(IOException::class)
  fun storePhoto(accessionId: AccessionId, data: InputStream, metadata: NewFileMetadata): FileId {
    requirePermissions { uploadPhoto(accessionId) }

    val fileId =
        fileService.storeFile("accession", data, metadata, imageUtils::read) { fileId ->
          accessionPhotosDao.insert(AccessionPhotosRow(accessionId = accessionId, fileId = fileId))
        }

    log.info("Stored photo $fileId for accession $accessionId")

    // "Overwrite" existing files with the same filename on the accession. This keeps the file with
    // the highest ID, which might not be the file we just stored (if there were two uploads in
    // progress at the same time).
    val filename = metadata.filename
    fetchFilesRows(accessionId, filename)
        .drop(1)
        .mapNotNull { it.id }
        .forEach { oldFileId ->
          log.info("Deleting earlier file $oldFileId for accession $accessionId photo $filename")
          // Deletes all rows and files except for the one with the highest ID.
          accessionPhotosDao.deleteById(oldFileId)
          eventPublisher.publishEvent(FileReferenceDeletedEvent(oldFileId))
        }

    return fileId
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
    return thumbnailService.readFile(row.id!!, maxWidth, maxHeight).withContentType(row.contentType)
  }

  /** Returns a list of metadata for an accession's photos. */
  fun listPhotos(accessionId: AccessionId): List<ExistingFileMetadata> {
    requirePermissions { readAccession(accessionId) }

    return dslContext
        .select(
            FILES.CONTENT_TYPE,
            FILES.FILE_NAME,
            FILES.ID,
            FILES.SIZE,
            FILES.STORAGE_URL,
        )
        .from(FILES)
        .join(ACCESSION_PHOTOS)
        .on(FILES.ID.eq(ACCESSION_PHOTOS.FILE_ID))
        .where(ACCESSION_PHOTOS.ACCESSION_ID.eq(accessionId))
        .orderBy(FILES.ID.desc())
        .fetch { record -> FileMetadata.of(record) }
        .distinctBy { it.filename }
  }

  /** Deletes one photo from an acession by filename */
  fun deletePhoto(accessionId: AccessionId, filename: String) {
    requirePermissions { updateAccession(accessionId) }

    fetchFilesRows(accessionId, filename)
        .mapNotNull { it.id }
        .forEach { fileId ->
          accessionPhotosDao.deleteById(fileId)
          eventPublisher.publishEvent(FileReferenceDeletedEvent(fileId))
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
          accessionPhotosDao.deleteById(fileId)
          eventPublisher.publishEvent(FileReferenceDeletedEvent(fileId))
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
   * Returns information about photos with a particular filename. The newest photo is first in the
   * list.
   */
  private fun fetchFilesRows(accessionId: AccessionId, filename: String): List<FilesRow> {
    return dslContext
        .select(FILES.asterisk())
        .from(FILES)
        .join(ACCESSION_PHOTOS)
        .on(FILES.ID.eq(ACCESSION_PHOTOS.FILE_ID))
        .where(ACCESSION_PHOTOS.ACCESSION_ID.eq(accessionId))
        .and(FILES.FILE_NAME.eq(filename))
        .orderBy(FILES.ID.desc())
        .fetchInto(FilesRow::class.java)
  }

  /**
   * Returns information about an existing photo.
   *
   * @throws NoSuchFileException There was no record of the photo.
   */
  private fun fetchFilesRow(accessionId: AccessionId, filename: String): FilesRow {
    return fetchFilesRows(accessionId, filename).firstOrNull()
        ?: throw NoSuchFileException(filename)
  }
}
