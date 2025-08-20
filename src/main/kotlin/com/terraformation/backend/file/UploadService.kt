package com.terraformation.backend.file

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.UploadNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.tables.daos.UploadsDao
import com.terraformation.backend.db.default_schema.tables.pojos.UploadsRow
import com.terraformation.backend.db.default_schema.tables.references.UPLOADS
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import java.nio.file.NoSuchFileException
import java.time.Duration
import java.time.InstantSource
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

@Named
class UploadService(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val fileStore: FileStore,
    private val uploadsDao: UploadsDao,
    private val uploadStore: UploadStore,
) {
  private val log = perClassLogger()

  /**
   * Receives an uploaded file from a client and stores it in the file store.
   *
   * @param [successStatus] Set the file's upload status to this after it is successfully stored.
   * @throws UploadFailedException Failed to read the uploaded file from the client, or failed to
   *   store it in the file store.
   */
  fun receive(
      inputStream: InputStream,
      fileName: String,
      contentType: String,
      type: UploadType,
      organizationId: OrganizationId? = null,
      facilityId: FacilityId? = null,
      successStatus: UploadStatus = UploadStatus.AwaitingValidation,
  ): UploadId {
    val url = fileStore.newUrl(clock.instant(), type.name, contentType)
    val uploadsRow =
        UploadsRow(
            contentType = contentType,
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            facilityId = facilityId,
            filename = fileName,
            locale = currentLocale(),
            organizationId = organizationId,
            statusId = UploadStatus.Receiving,
            storageUrl = url,
            typeId = type,
        )

    uploadsDao.insert(uploadsRow)
    val uploadId = uploadsRow.id ?: throw IllegalStateException("ID not populated")

    try {
      fileStore.write(url, inputStream)

      uploadsRow.statusId = successStatus
      uploadsDao.update(uploadsRow)
    } catch (e: Exception) {
      log.error("Unable to store uploaded file", e)

      try {
        fileStore.delete(url)
        uploadsDao.delete(uploadsRow)
      } catch (_: NoSuchFileException) {
        // Expected; file might not have been created successfully.
        uploadsDao.delete(uploadsRow)
      } catch (deleteEx: Exception) {
        log.error("Failed to delete uploaded file after upload failure", deleteEx)

        // Keep a record of the file around so we can clean it up later.
        uploadsRow.statusId = UploadStatus.ReceivingFailed
        uploadsDao.update(uploadsRow)
      }

      throw UploadFailedException(e)
    }

    log.info("Upload $uploadId of type $type saved to $url")

    return uploadId
  }

  fun delete(uploadId: UploadId) {
    requirePermissions { deleteUpload(uploadId) }

    val uploadsRow = uploadsDao.fetchOneById(uploadId) ?: throw UploadNotFoundException(uploadId)

    val storageUrl =
        uploadsRow.storageUrl ?: throw IllegalStateException("Storage URL must be non-null")
    try {
      fileStore.delete(storageUrl)
    } catch (e: NoSuchFileException) {
      log.error("File $storageUrl for upload ${uploadsRow.id} not found")
    }

    uploadStore.delete(uploadId)

    log.info("Uploaded file ${uploadsRow.id} deleted")
  }

  /**
   * Expires old uploaded files. Depending on the file type, the actual file may be removed from the
   * file store.
   */
  @EventListener
  fun expireOldUploads(@Suppress("UNUSED_PARAMETER") event: DailyTaskTimeArrivedEvent) {
    val records =
        dslContext
            .select(UPLOADS.ID, UPLOADS.TYPE_ID, UPLOADS.STORAGE_URL)
            .from(UPLOADS)
            .where(UPLOADS.CREATED_TIME.le(clock.instant() - EXPIRATION_TIME))
            .fetch()

    log.info("Found ${records.size} old uploads to expire")

    records.forEach { (uploadId, typeId, storageUrl) ->
      if (uploadId == null || typeId == null || storageUrl == null) {
        throw IllegalStateException(
            "BUG! Query returned null value ($uploadId $typeId $storageUrl)"
        )
      }

      if (typeId.expireFiles) {
        try {
          fileStore.delete(storageUrl)
          log.debug("Deleted expired upload $uploadId file $storageUrl")
        } catch (e: NoSuchFileException) {
          log.warn("Expired upload $uploadId file $storageUrl was already deleted")
        } catch (e: Exception) {
          log.error("Unable to delete upload $uploadId file $storageUrl", e)
          // Keep the database row around so we'll retry the deletion tomorrow in case it was a
          // transient error.
          return@forEach
        }
      }

      uploadStore.delete(uploadId)

      log.debug("Deleted expired upload $uploadId")
    }
  }

  @EventListener
  fun on(event: OrganizationDeletionStartedEvent) {
    uploadStore.fetchIdsByOrganization(event.organizationId).forEach { delete(it) }
  }

  companion object {
    val EXPIRATION_TIME = Duration.ofDays(7)!!
  }
}
