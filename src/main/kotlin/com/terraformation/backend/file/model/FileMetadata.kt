package com.terraformation.backend.file.model

import com.terraformation.backend.db.asNonNullable
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
      filename = record[FILES.FILE_NAME.asNonNullable()],
      contentType = record[FILES.CONTENT_TYPE] ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
      size = record[FILES.SIZE.asNonNullable()],
  )
}
