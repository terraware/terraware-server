package com.terraformation.backend.file

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.UploadNotAwaitingActionException
import com.terraformation.backend.db.UploadNotFoundException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadsDao
import com.terraformation.backend.db.default_schema.tables.references.UPLOADS
import com.terraformation.backend.db.default_schema.tables.references.UPLOAD_PROBLEMS
import com.terraformation.backend.file.model.UploadModel
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class UploadStore(
    private val dslContext: DSLContext,
    private val uploadProblemsDao: UploadProblemsDao,
    private val uploadsDao: UploadsDao,
) {
  fun fetchOneById(uploadId: UploadId): UploadModel {
    requirePermissions { readUpload(uploadId) }

    val uploadsRow = uploadsDao.fetchOneById(uploadId) ?: throw UploadNotFoundException(uploadId)
    val problemRows = uploadProblemsDao.fetchByUploadId(uploadId)

    return UploadModel(uploadsRow, problemRows)
  }

  fun fetchIdsByOrganization(organizationId: OrganizationId): List<UploadId> {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .select(UPLOADS.ID)
        .from(UPLOADS)
        .where(UPLOADS.ORGANIZATION_ID.eq(organizationId))
        .fetch(UPLOADS.ID)
        .filterNotNull()
        .filter { currentUser().canReadUpload(it) }
  }

  fun deleteProblems(uploadId: UploadId) {
    requirePermissions { updateUpload(uploadId) }

    dslContext.deleteFrom(UPLOAD_PROBLEMS).where(UPLOAD_PROBLEMS.UPLOAD_ID.eq(uploadId)).execute()
  }

  fun delete(uploadId: UploadId) {
    requirePermissions { deleteUpload(uploadId) }

    dslContext.deleteFrom(UPLOADS).where(UPLOADS.ID.eq(uploadId)).execute()
  }

  /** Updates an upload's status. */
  fun updateStatus(uploadId: UploadId, status: UploadStatus) {
    requirePermissions { updateUpload(uploadId) }

    dslContext
        .update(UPLOADS)
        .set(UPLOADS.STATUS_ID, status)
        .where(UPLOADS.ID.eq(uploadId))
        .execute()
  }

  /**
   * Throws [UploadNotAwaitingActionException] if the upload is not currently awaiting user action.
   */
  fun requireAwaitingAction(uploadId: UploadId) {
    requirePermissions { readUpload(uploadId) }

    val uploadsRow = uploadsDao.fetchOneById(uploadId) ?: throw UploadNotFoundException(uploadId)
    if (uploadsRow.statusId != UploadStatus.AwaitingUserAction) {
      throw UploadNotAwaitingActionException(uploadId)
    }
  }
}
