package com.terraformation.backend.file

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Clock
import javax.inject.Named
import org.jooq.DSLContext

/**
 * Manages storage of files including metadata. In this implementation, files are stored on the
 * filesystem and metadata in the database.
 */
@Named
class FileService(
    private val dslContext: DSLContext,
    private val clock: Clock,
    private val filesDao: FilesDao,
    private val fileStore: FileStore,
    private val thumbnailStore: ThumbnailStore,
) {
  private val log = perClassLogger()

  @Throws(IOException::class)
  fun storeFile(
      category: String,
      data: InputStream,
      metadata: NewFileMetadata,
      insertChildRows: (FileId) -> Unit
  ): FileId {
    val storageUrl = fileStore.newUrl(clock.instant(), category, metadata.contentType)

    try {
      fileStore.write(storageUrl, data)

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
    } catch (e: FileAlreadyExistsException) {
      // Don't delete the existing file
      throw e
    } catch (e: Exception) {
      try {
        fileStore.delete(storageUrl)
      } catch (ignore: NoSuchFileException) {
        // Swallow this; file is already deleted
      }
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
        .fetchOne(FILES.STORAGE_URL)
        ?: throw FileNotFoundException(fileId)
  }
}
