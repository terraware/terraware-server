package com.terraformation.backend.file

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.UploadId
import com.terraformation.backend.db.UploadNotFoundException
import com.terraformation.backend.db.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.tables.daos.UploadsDao
import com.terraformation.backend.db.tables.references.UPLOADS
import com.terraformation.backend.db.tables.references.UPLOAD_PROBLEMS
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

  fun deleteProblems(uploadId: UploadId) {
    requirePermissions { updateUpload(uploadId) }

    dslContext.deleteFrom(UPLOAD_PROBLEMS).where(UPLOAD_PROBLEMS.UPLOAD_ID.eq(uploadId)).execute()
  }

  fun delete(uploadId: UploadId) {
    requirePermissions { deleteUpload(uploadId) }

    dslContext.transaction { _ ->
      dslContext.deleteFrom(UPLOAD_PROBLEMS).where(UPLOAD_PROBLEMS.UPLOAD_ID.eq(uploadId)).execute()
      dslContext.deleteFrom(UPLOADS).where(UPLOADS.ID.eq(uploadId)).execute()
    }
  }
}
