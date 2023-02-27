package com.terraformation.backend.file.model

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.references.FILES
import java.net.URI
import org.jooq.Record
import org.springframework.http.MediaType

data class FileMetadata<I : FileId?, U : URI?>(
    val contentType: String,
    val filename: String,
    val id: I,
    val size: Long,
    val storageUrl: U,
) {
  /** The filename with any directory names stripped off. */
  val filenameWithoutPath: String
    get() = filename.substringAfterLast('/').substringAfterLast('\\')

  companion object {
    fun of(row: FilesRow): ExistingFileMetadata =
        ExistingFileMetadata(
            contentType = row.contentType ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
            filename = row.fileName!!,
            id = row.id!!,
            size = row.size!!,
            storageUrl = row.storageUrl!!,
        )

    fun of(record: Record): ExistingFileMetadata =
        ExistingFileMetadata(
            contentType = record[FILES.CONTENT_TYPE] ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
            filename = record[FILES.FILE_NAME.asNonNullable()],
            id = record[FILES.ID.asNonNullable()],
            size = record[FILES.SIZE.asNonNullable()],
            storageUrl = record[FILES.STORAGE_URL.asNonNullable()],
        )

    fun of(contentType: String, filename: String, size: Long): NewFileMetadata =
        NewFileMetadata(
            contentType = contentType,
            filename = filename,
            id = null,
            size = size,
            storageUrl = null,
        )
  }
}

typealias NewFileMetadata = FileMetadata<Nothing?, Nothing?>

typealias ExistingFileMetadata = FileMetadata<FileId, URI>
