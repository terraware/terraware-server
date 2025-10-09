package com.terraformation.backend.file

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.DefaultCatalog
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.TokenNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.FILE_ACCESS_TOKENS
import com.terraformation.backend.db.default_schema.tables.references.MUX_ASSETS
import com.terraformation.backend.db.default_schema.tables.references.THUMBNAILS
import com.terraformation.backend.file.event.FileDeletionStartedEvent
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
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
import org.jooq.TableField
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

/** Manages storage of files including metadata. */
@Named
class FileService(
    private val dslContext: DSLContext,
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val eventPublisher: ApplicationEventPublisher,
    private val filesDao: FilesDao,
    private val fileStore: FileStore,
) {
  private val log = perClassLogger()

  /**
   * Fields that are foreign key references to the files table but that should not count as active
   * references when we're scanning to see if a file is still in use anywhere. These will typically
   * be for things that are derived from the file and should be deleted when the file is deleted,
   * e.g., thumbnails.
   */
  private val inactiveReferringFields =
      setOf(
          FILE_ACCESS_TOKENS.FILE_ID,
          MUX_ASSETS.FILE_ID,
          THUMBNAILS.FILE_ID,
      )

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
  fun readFile(fileId: FileId): SizedInputStream {
    val filesRow = filesDao.fetchOneById(fileId) ?: throw FileNotFoundException(fileId)
    return fileStore.read(filesRow.storageUrl!!).withContentType(filesRow.contentType)
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
  fun on(event: FileReferenceDeletedEvent) {
    val fileId = event.fileId
    if (!isReferenced(fileId)) {
      val filesRow = filesDao.fetchOneById(fileId)
      val storageUrl = filesRow?.storageUrl

      if (storageUrl == null) {
        log.error("Reference to file $fileId was deleted but the file does not exist")
        return
      }

      // Clean up thumbnails, Mux assets, etc.
      eventPublisher.publishEvent(FileDeletionStartedEvent(event.fileId, filesRow.contentType!!))

      try {
        fileStore.delete(storageUrl)
      } catch (_: NoSuchFileException) {
        log.warn("File $storageUrl was already deleted from file store")
      }

      filesDao.deleteById(fileId)
    }
  }

  @EventListener
  fun on(@Suppress("unused") event: DailyTaskTimeArrivedEvent) {
    try {
      dslContext
          .deleteFrom(FILE_ACCESS_TOKENS)
          .where(FILE_ACCESS_TOKENS.EXPIRES_TIME.le(clock.instant()))
          .execute()
    } catch (e: Exception) {
      log.error("Unable to prune file access tokens", e)
    }
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
    } catch (_: NoSuchFileException) {
      // Swallow this; file is already deleted
    }
  }

  /** Returns true if a file is referenced by any application-specific entities. */
  private fun isReferenced(fileId: FileId): Boolean {
    val existsConditions =
        activeReferringFields.map { field ->
          DSL.exists(DSL.selectOne().from(field.table).where(field.eq(fileId)))
        }

    return dslContext.select(DSL.or(existsConditions)).fetchSingle().value1()
  }

  /**
   * Fields that count as active references to files. If a file is referenced in any of these, it
   * will not be deleted from the file store by the [FileReferenceDeletedEvent] handler.
   *
   * This doesn't necessarily include all the foreign-key relationships with the files table, just
   * ones whose presence should cause the file to be considered still in use. The fields in
   * [inactiveReferringFields] are excluded from this list.
   */
  @Suppress("UNCHECKED_CAST")
  private val activeReferringFields: Collection<TableField<*, FileId?>> by lazy {
    DefaultCatalog.DEFAULT_CATALOG.schemas.flatMap { schema ->
      schema.tables.flatMap { table ->
        table.references
            .filter { reference -> reference.key.fields == listOf(FILES.ID) }
            .map { it.fields.first() as TableField<*, FileId?> }
            .filterNot { it in inactiveReferringFields }
      }
    }
  }
}
