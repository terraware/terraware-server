package com.terraformation.backend.file.model

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.references.FILES
import java.net.URI
import java.time.LocalDateTime
import org.jooq.Record
import org.locationtech.jts.geom.Point
import org.springframework.http.MediaType

data class FileMetadata<I : FileId?, U : URI?>(
    val contentType: String,
    val filename: String,
    val id: I,
    val size: Long,
    val storageUrl: U,
    val capturedLocalTime: LocalDateTime? = null,
    val geolocation: Point? = null,
) {
  /** The filename with any directory names stripped off. */
  val filenameWithoutPath: String
    get() = filename.substringAfterLast('/').substringAfterLast('\\')

  companion object {
    fun of(row: FilesRow): ExistingFileMetadata =
        ExistingFileMetadata(
            capturedLocalTime = row.capturedLocalTime,
            contentType = row.contentType ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
            filename = row.fileName!!,
            geolocation = row.geolocation?.centroid,
            id = row.id!!,
            size = row.size!!,
            storageUrl = row.storageUrl!!,
        )

    fun of(record: Record): ExistingFileMetadata =
        ExistingFileMetadata(
            capturedLocalTime = record[FILES.CAPTURED_LOCAL_TIME],
            contentType = record[FILES.CONTENT_TYPE] ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
            filename = record[FILES.FILE_NAME.asNonNullable()],
            geolocation = record[FILES.GEOLOCATION]?.centroid,
            id = record[FILES.ID.asNonNullable()],
            size = record[FILES.SIZE.asNonNullable()],
            storageUrl = record[FILES.STORAGE_URL.asNonNullable()],
        )

    fun of(
        contentType: String,
        filename: String,
        size: Long,
        geolocation: Point? = null,
    ): NewFileMetadata =
        NewFileMetadata(
            contentType = contentType,
            filename = filename,
            geolocation = geolocation,
            id = null,
            size = size,
            storageUrl = null,
        )
  }
}

typealias NewFileMetadata = FileMetadata<Nothing?, Nothing?>

typealias ExistingFileMetadata = FileMetadata<FileId, URI>
