package com.terraformation.backend.nursery.db

import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.tables.daos.WithdrawalPhotosDao
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalPhotosRow
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_PHOTOS
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import org.jooq.Condition
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class WithdrawalPhotoService(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val fileService: FileService,
    private val thumbnailService: ThumbnailService,
    private val withdrawalPhotosDao: WithdrawalPhotosDao,
) {
  private val log = perClassLogger()

  fun storePhoto(withdrawalId: WithdrawalId, data: InputStream, metadata: NewFileMetadata): FileId {
    requirePermissions { createWithdrawalPhoto(withdrawalId) }

    val fileId =
        fileService.storeFile("withdrawal", data, metadata) { fileId ->
          withdrawalPhotosDao.insert(
              WithdrawalPhotosRow(fileId = fileId, withdrawalId = withdrawalId)
          )
        }

    log.info("Stored photo $fileId for withdrawal $withdrawalId")

    return fileId
  }

  fun readPhoto(
      withdrawalId: WithdrawalId,
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    val storedWithdrawalId =
        dslContext
            .select(WITHDRAWAL_PHOTOS.WITHDRAWAL_ID)
            .from(WITHDRAWAL_PHOTOS)
            .where(WITHDRAWAL_PHOTOS.FILE_ID.eq(fileId))
            .fetchOne(WITHDRAWAL_PHOTOS.WITHDRAWAL_ID)
    if (withdrawalId != storedWithdrawalId) {
      throw FileNotFoundException(fileId)
    }

    requirePermissions { readWithdrawal(withdrawalId) }

    return thumbnailService.readFile(fileId, maxWidth, maxHeight)
  }

  fun listPhotos(withdrawalId: WithdrawalId): List<FileId> {
    requirePermissions { readWithdrawal(withdrawalId) }

    return dslContext
        .select(WITHDRAWAL_PHOTOS.FILE_ID)
        .from(WITHDRAWAL_PHOTOS)
        .where(WITHDRAWAL_PHOTOS.WITHDRAWAL_ID.eq(withdrawalId))
        .fetch(WITHDRAWAL_PHOTOS.FILE_ID.asNonNullable())
  }

  /** Deletes all the photos from all the withdrawals owned by an organization. */
  @EventListener
  fun on(event: OrganizationDeletionStartedEvent) {
    deleteWhere(
        WITHDRAWAL_PHOTOS.withdrawals.withdrawalsFacilityIdFkey.ORGANIZATION_ID.eq(
            event.organizationId
        )
    )
  }

  private fun deleteWhere(condition: Condition) {
    with(WITHDRAWAL_PHOTOS) {
      dslContext
          .select(FILE_ID)
          .from(WITHDRAWAL_PHOTOS)
          .where(condition)
          .fetch(FILE_ID.asNonNullable())
          .forEach { fileId ->
            withdrawalPhotosDao.deleteById(fileId)
            eventPublisher.publishEvent(FileReferenceDeletedEvent(fileId))
          }
    }
  }
}
