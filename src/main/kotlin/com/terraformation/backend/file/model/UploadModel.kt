package com.terraformation.backend.file.model

import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.UploadProblemsRow
import com.terraformation.backend.db.default_schema.tables.pojos.UploadsRow
import java.net.URI

data class UploadProblemModel(
    val field: String?,
    val id: UploadProblemId,
    val isError: Boolean,
    val message: String?,
    val position: Int?,
    val type: UploadProblemType,
    val value: String?,
) {
  constructor(
      row: UploadProblemsRow
  ) : this(
      field = row.field,
      id = row.id ?: throw IllegalArgumentException("ID must be non-null"),
      isError = row.isError == true,
      message = row.message,
      position = row.position,
      type = row.typeId ?: throw IllegalArgumentException("Type must be non-null"),
      value = row.value,
  )
}

data class UploadModel(
    val contentType: String,
    val createdBy: UserId,
    val errors: List<UploadProblemModel>,
    /** Client-supplied filename. */
    val filename: String,
    val id: UploadId,
    val storageUrl: URI,
    val status: UploadStatus,
    val type: UploadType,
    val warnings: List<UploadProblemModel>,
) {
  constructor(
      row: UploadsRow,
      problemRows: List<UploadProblemsRow>,
  ) : this(
      contentType =
          row.contentType ?: throw IllegalArgumentException("Content type must be non-null"),
      createdBy = row.createdBy ?: throw IllegalArgumentException("Created by must be non-null"),
      errors = problemRows.filter { it.isError == true }.map { UploadProblemModel(it) },
      filename = row.filename ?: throw IllegalArgumentException("Filename must be non-null"),
      id = row.id ?: throw IllegalArgumentException("ID must be non-null"),
      storageUrl = row.storageUrl ?: throw IllegalArgumentException("Storage URL must be non-null"),
      status = row.statusId ?: throw IllegalArgumentException("Status must be non-null"),
      type = row.typeId ?: throw IllegalArgumentException("Type must be non-null"),
      warnings = problemRows.filter { it.isError == false }.map { UploadProblemModel(it) },
  )
}
