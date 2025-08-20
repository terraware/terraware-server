package com.terraformation.backend.file

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Clock
import org.jooq.DSLContext

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

  /** Deletes a file and swallows the NoSuchFileException if it doesn't exist. */
  private fun deleteIfExists(storageUrl: URI) {
    try {
      fileStore.delete(storageUrl)
    } catch (ignore: NoSuchFileException) {
      // Swallow this; file is already deleted
    }
  }
}
