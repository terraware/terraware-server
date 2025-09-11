package com.terraformation.backend.file

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.TokenNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.FILE_ACCESS_TOKENS
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

/**
 * Manages storage of files including metadata. In this implementation, files are stored on the
 * filesystem and metadata in the database.
 */
@Named
class FileService(
    private val dslContext: DSLContext,
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val filesDao: FilesDao,
    private val fileStore: FileStore,
    private val thumbnailStore: ThumbnailStore,
) {
  private val log = perClassLogger()

  /**
   * Stores a file on the file store and records its information in the database.
   *
   * @param validateFile Function to check that the file's contents are valid. If not, this should
   *   throw an exception.
   * @param insertChildRows Function to write any additional use-case-specific data about the file.
   *   Called after the file's basic information has been inserted into the files table, and called
   *   in the same transaction that inserts into the files table. If this throws an exception, the
   *   transaction is rolled back and the file is deleted from the file store.
   */
  @Throws(IOException::class)
  fun storeFile(
      category: String,
      data: InputStream,
      metadata: NewFileMetadata,
      validateFile: ((URI) -> Unit)? = null,
      insertChildRows: (FileId) -> Unit,
  ): FileId {
    val storageUrl = fileStore.newUrl(clock.instant(), category, metadata.contentType)

    try {
      fileStore.write(storageUrl, data)
    } catch (e: FileAlreadyExistsException) {
      // Don't delete the existing file
      throw e
    } catch (e: Exception) {
      deleteIfExists(storageUrl)
      throw e
    }

    try {
      validateFile?.invoke(storageUrl)
    } catch (e: Exception) {
      if (!config.keepInvalidUploads) {
        deleteIfExists(storageUrl)
      } else {
        log.warn("File $storageUrl failed validation; keeping it for examination", e)
      }

      throw e
    }

    try {
      val filesRow =
          FilesRow(
              contentType = metadata.contentType,
              createdTime = clock.instant(),
              createdBy = currentUser().userId,
              fileName = metadata.filename,
              modifiedBy = currentUser().userId,
              modifiedTime = clock.instant(),
              size = metadata.size,
              storageUrl = storageUrl,
          )

      dslContext.transaction { _ ->
        filesDao.insert(filesRow)
        insertChildRows(filesRow.id!!)
      }

      return filesRow.id!!
    } catch (e: Exception) {
      deleteIfExists(storageUrl)
      throw e
    }
  }

  @Throws(IOException::class)
  fun readFile(
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    return if (maxWidth != null || maxHeight != null) {
      thumbnailStore.getThumbnailData(fileId, maxWidth, maxHeight)
    } else {
      val filesRow = filesDao.fetchOneById(fileId) ?: throw FileNotFoundException(fileId)
      fileStore.read(filesRow.storageUrl!!).withContentType(filesRow.contentType)
    }
  }

  /**
   * Deletes a file and its thumbnails.
   *
   * @param deleteChildRows Deletes any rows from child tables that refer to the files table. This
   *   is called in a transaction before the files table row is deleted.
   */
  fun deleteFile(fileId: FileId, deleteChildRows: () -> Unit) {
    val storageUrl = fetchUrl(fileId)
    thumbnailStore.deleteThumbnails(fileId)

    try {
      fileStore.delete(storageUrl)
    } catch (e: NoSuchFileException) {
      log.warn("File $storageUrl was already deleted from file store")
    }

    dslContext.transaction { _ ->
      deleteChildRows()
      filesDao.deleteById(fileId)
    }
  }

  fun createToken(fileId: FileId, expiration: Duration): String {
    ensureFileExists(fileId)

    val now = clock.instant()
    val expires = now + expiration
    val token = UUID.randomUUID().toString()

    with(FILE_ACCESS_TOKENS) {
      dslContext
          .insertInto(FILE_ACCESS_TOKENS)
          .set(CREATED_BY, currentUser().userId)
          .set(CREATED_TIME, now)
          .set(EXPIRES_TIME, expires)
          .set(FILE_ID, fileId)
          .set(TOKEN, token)
          .execute()
    }

    return token
  }

  fun readFileForToken(token: String): SizedInputStream {
    val fileId =
        dslContext.fetchValue(
            FILE_ACCESS_TOKENS.FILE_ID,
            FILE_ACCESS_TOKENS.TOKEN.eq(token)
                .and(FILE_ACCESS_TOKENS.EXPIRES_TIME.gt(clock.instant())),
        ) ?: throw TokenNotFoundException(token)

    return readFile(fileId)
  }

  @EventListener
  fun on(event: DailyTaskTimeArrivedEvent) {
    try {
      dslContext
          .deleteFrom(FILE_ACCESS_TOKENS)
          .where(FILE_ACCESS_TOKENS.EXPIRES_TIME.le(clock.instant()))
          .execute()
    } catch (e: Exception) {
      log.error("Unable to prune file access tokens", e)
    }
  }

  /**
   * Returns the storage URL of an existing file.
   *
   * @throws FileNotFoundException There was no record of the file.
   */
  private fun fetchUrl(fileId: FileId): URI {
    return dslContext
        .select(FILES.STORAGE_URL)
        .from(FILES)
        .where(FILES.ID.eq(fileId))
        .fetchOne(FILES.STORAGE_URL) ?: throw FileNotFoundException(fileId)
  }

  private fun ensureFileExists(fileId: FileId) {
    if (!dslContext.fetchExists(FILES, FILES.ID.eq(fileId))) {
      throw FileNotFoundException(fileId)
    }
  }

  /** Deletes a file and swallows the NoSuchFileException if it doesn't exist. */
  private fun deleteIfExists(storageUrl: URI) {
    try {
      fileStore.delete(storageUrl)
    } catch (ignore: NoSuchFileException) {
      // Swallow this; file is already deleted
    }
  }
}
