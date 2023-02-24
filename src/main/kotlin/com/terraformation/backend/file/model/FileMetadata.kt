package com.terraformation.backend.file.model

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.references.FILES
import org.jooq.Record
import org.springframework.http.MediaType

data class FileMetadata(
    val filename: String,
    val contentType: String,
    val size: Long,
) {
  constructor(
      record: Record
  ) : this(
      contentType = record[FILES.CONTENT_TYPE] ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
      filename = record[FILES.FILE_NAME.asNonNullable()],
      size = record[FILES.SIZE.asNonNullable()],
  )

  constructor(
      row: FilesRow
  ) : this(
      contentType = row.contentType ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
      filename = row.fileName!!,
      size = row.size!!,
  )

  /** The filename with any directory names stripped off. */
  val filenameWithoutPath: String
    get() = filename.substringAfterLast('/').substringAfterLast('\\')
}
