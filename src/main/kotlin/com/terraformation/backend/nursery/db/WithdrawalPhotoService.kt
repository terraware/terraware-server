package com.terraformation.backend.nursery.db

import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.PhotoNotFoundException
import com.terraformation.backend.db.default_schema.PhotoId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.tables.daos.WithdrawalPhotosDao
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalPhotosRow
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_PHOTOS
import com.terraformation.backend.file.PhotoService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.model.PhotoMetadata
import com.terraformation.backend.log.perClassLogger
import java.io.InputStream
import javax.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

@Named
class WithdrawalPhotoService(
    private val dslContext: DSLContext,
    private val photoService: PhotoService,
    private val withdrawalPhotosDao: WithdrawalPhotosDao,
) {
  private val log = perClassLogger()

  fun storePhoto(
      withdrawalId: WithdrawalId,
      data: InputStream,
      size: Long,
      metadata: PhotoMetadata
  ): PhotoId {
    requirePermissions { createWithdrawalPhoto(withdrawalId) }

    val photoId =
        photoService.storePhoto("withdrawal", data, size, metadata) { photoId ->
          withdrawalPhotosDao.insert(
              WithdrawalPhotosRow(photoId = photoId, withdrawalId = withdrawalId))
        }

    log.info("Stored photo $photoId for withdrawal $withdrawalId")

    return photoId
  }

  fun readPhoto(
      withdrawalId: WithdrawalId,
      photoId: PhotoId,
      maxWidth: Int? = null,
      maxHeight: Int? = null
  ): SizedInputStream {
    val storedWithdrawalId =
        dslContext
            .select(WITHDRAWAL_PHOTOS.WITHDRAWAL_ID)
            .from(WITHDRAWAL_PHOTOS)
            .where(WITHDRAWAL_PHOTOS.PHOTO_ID.eq(photoId))
            .fetchOne(WITHDRAWAL_PHOTOS.WITHDRAWAL_ID)
    if (withdrawalId != storedWithdrawalId) {
      throw PhotoNotFoundException(photoId)
    }

    requirePermissions { readWithdrawal(withdrawalId) }

    return photoService.readPhoto(photoId, maxWidth, maxHeight)
  }

  fun listPhotos(withdrawalId: WithdrawalId): List<PhotoId> {
    requirePermissions { readWithdrawal(withdrawalId) }

    return dslContext
        .select(WITHDRAWAL_PHOTOS.PHOTO_ID)
        .from(WITHDRAWAL_PHOTOS)
        .where(WITHDRAWAL_PHOTOS.WITHDRAWAL_ID.eq(withdrawalId))
        .fetch(WITHDRAWAL_PHOTOS.PHOTO_ID)
        .filterNotNull()
  }

  /** Deletes all the photos from all the withdrawals owned by an organization. */
  @EventListener
  fun on(event: OrganizationDeletionStartedEvent) {
    with(WITHDRAWAL_PHOTOS) {
      dslContext
          .select(PHOTO_ID)
          .from(WITHDRAWAL_PHOTOS)
          .where(withdrawals.withdrawalsFacilityIdFkey.ORGANIZATION_ID.eq(event.organizationId))
          .fetch(PHOTO_ID)
          .filterNotNull()
          .forEach { photoId ->
            photoService.deletePhoto(photoId) { withdrawalPhotosDao.deleteById(photoId) }
          }
    }
  }
}
