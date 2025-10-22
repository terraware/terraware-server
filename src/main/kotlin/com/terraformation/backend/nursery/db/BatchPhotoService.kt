package com.terraformation.backend.nursery.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.tables.daos.BatchPhotosDao
import com.terraformation.backend.db.nursery.tables.pojos.BatchPhotosRow
import com.terraformation.backend.db.nursery.tables.references.BATCH_PHOTOS
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.event.BatchDeletionStartedEvent
import jakarta.inject.Named
import java.io.InputStream
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class BatchPhotoService(
    private val batchPhotosDao: BatchPhotosDao,
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val fileService: FileService,
    private val thumbnailService: ThumbnailService,
) {
  private val log = perClassLogger()

  fun storePhoto(batchId: BatchId, data: InputStream, metadata: NewFileMetadata): FileId {
    requirePermissions { updateBatch(batchId) }

    val fileId =
        fileService.storeFile("batch", data, metadata) { fileId ->
          batchPhotosDao.insert(
              BatchPhotosRow(
                  batchId = batchId,
                  createdBy = currentUser().userId,
                  createdTime = clock.instant(),
                  fileId = fileId,
              )
          )
        }

    log.info("Stored photo $fileId for batch $batchId")

    return fileId
  }

  fun readPhoto(
      batchId: BatchId,
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    checkFileExists(batchId, fileId)

    requirePermissions { readBatch(batchId) }

    return thumbnailService.readFile(fileId, maxWidth, maxHeight)
  }

  fun listPhotos(batchId: BatchId): List<BatchPhotosRow> {
    requirePermissions { readBatch(batchId) }

    return dslContext
        .selectFrom(BATCH_PHOTOS)
        .where(BATCH_PHOTOS.BATCH_ID.eq(batchId))
        .and(BATCH_PHOTOS.DELETED_TIME.isNull)
        .orderBy(BATCH_PHOTOS.ID)
        .fetchInto(BatchPhotosRow::class.java)
  }

  fun deletePhoto(batchId: BatchId, fileId: FileId) {
    checkFileExists(batchId, fileId)

    requirePermissions { updateBatch(batchId) }

    dslContext
        .update(BATCH_PHOTOS)
        .set(BATCH_PHOTOS.DELETED_BY, currentUser().userId)
        .set(BATCH_PHOTOS.DELETED_TIME, clock.instant())
        .setNull(BATCH_PHOTOS.FILE_ID)
        .where(BATCH_PHOTOS.BATCH_ID.eq(batchId))
        .and(BATCH_PHOTOS.FILE_ID.eq(fileId))
        .execute()

    eventPublisher.publishEvent(FileReferenceDeletedEvent(fileId))
  }

  /** Deletes all the photos from all the batches owned by an organization. */
  @EventListener
  fun on(event: OrganizationDeletionStartedEvent) {
    purgeWhere(BATCH_PHOTOS.batches.ORGANIZATION_ID.eq(event.organizationId))
  }

  /** Deletes all the photos from a batch when the batch is deleted. */
  @EventListener
  fun on(event: BatchDeletionStartedEvent) {
    purgeWhere(BATCH_PHOTOS.BATCH_ID.eq(event.batchId))
  }

  private fun checkFileExists(batchId: BatchId, fileId: FileId) {
    val fileExists =
        dslContext.fetchExists(
            DSL.selectOne()
                .from(BATCH_PHOTOS)
                .where(BATCH_PHOTOS.BATCH_ID.eq(batchId))
                .and(BATCH_PHOTOS.FILE_ID.eq(fileId))
        )
    if (!fileExists) {
      throw FileNotFoundException(fileId)
    }
  }

  /**
   * Purges batch photos matching a condition. This removes the file, if any, and also deletes the
   * batch photo record from the database (as opposed to just marking it as deleted).
   */
  private fun purgeWhere(condition: Condition) {
    with(BATCH_PHOTOS) {
      dslContext
          .select(ID.asNonNullable(), FILE_ID)
          .from(BATCH_PHOTOS)
          .where(condition)
          .fetch()
          .forEach { (batchPhotoId, fileId) ->
            if (fileId != null) {
              batchPhotosDao.deleteById(batchPhotoId)
              eventPublisher.publishEvent(FileReferenceDeletedEvent(fileId))
            } else {
              // The photo file is already deleted, but we kept a record of its history.
              batchPhotosDao.deleteById(batchPhotoId)
            }
          }
    }
  }
}
