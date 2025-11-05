package com.terraformation.backend.file

import com.drew.imaging.FileType
import com.drew.imaging.FileTypeDetector
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.terraformation.backend.auth.currentUser
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
import com.terraformation.backend.file.event.VideoFileUploadedEvent
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.InputStreamCopier
import jakarta.inject.Named
import java.io.BufferedInputStream
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
   * Stores a file on the file store and records its information in the database. If optional
   * metadata such as geolocation isn't supplied by the caller but can be extracted from the file,
   * records that information as well.
   *
   * The order of precedence for optional metadata (lower numbers are preferred):
   * 1. The values supplied by the [populateMetadata] callback, if any.
   * 2. The values supplied by the caller in the [newFileMetadata] argument, if any.
   * 3. The values extracted from the file, if any.
   *
   * @param populateMetadata Function to populate any metadata properties whose values aren't known
   *   initially, or to override values that are extracted from the file. Called after the file has
   *   been written to the file store but before the file's information has been inserted into the
   *   database.
   * @param insertChildRows Function to write any additional use-case-specific data about the file.
   *   Called after the file's basic information has been inserted into the files table, and called
   *   in the same transaction that inserts into the files table. If this throws an exception, the
   *   transaction is rolled back and the file is deleted from the file store.
   */
  @Throws(IOException::class)
  fun storeFile(
      category: String,
      data: InputStream,
      newFileMetadata: NewFileMetadata,
      populateMetadata: ((NewFileMetadata) -> NewFileMetadata)? = null,
      insertChildRows: (StoredFile) -> Unit,
  ): FileId {
    val copier = InputStreamCopier(data)
    val fileTypeStream = copier.getCopy()
    val exifStream = copier.getCopy()
    val storageStream = copier.getCopy()

    // Use a child thread to transfer data to the copy streams and the current thread to save the
    // file to the file store, rather than the other way around, so that the call to
    // fileService.storeFile() will use this thread's active database transaction.
    val currentThreadName = Thread.currentThread().name
    Thread.ofVirtual().name("$currentThreadName-transfer").start { copier.transfer() }

    var fileType: FileType? = null
    var exifMetadata: Metadata? = null

    val exifThread =
        Thread.ofVirtual().name("$currentThreadName-exif").start {
          exifStream.use {
            try {
              // Wrap the input in a BufferedInputStream because detectFileType calls mark/reset on
              // it. But that's only done to read a small amount of header data at the beginning of
              // the file to determine the file type. (That's also why we can safely read both
              // fileTypeStream and exifStream in the same thread.) After that, we throw the
              // buffered wrapper away and close the copy stream; another copy stream is used to
              // read the actual EXIF metadata to avoid BufferedInputStream copying bytes around
              // needlessly.
              fileType =
                  fileTypeStream.use { FileTypeDetector.detectFileType(BufferedInputStream(it)) }

              if (fileType != FileType.Unknown) {
                exifMetadata = ImageMetadataReader.readMetadata(exifStream, -1, fileType)
              }
            } catch (e: Exception) {
              log.error("Failed to extract EXIF data from uploaded file", e)
            }
          }
        }

    return storageStream.use {
      val storageUrl = fileStore.newUrl(clock.instant(), category, newFileMetadata.contentType)

      try {
        fileStore.write(storageUrl, storageStream)
      } catch (e: FileAlreadyExistsException) {
        // Don't delete the existing file
        throw e
      } catch (e: Exception) {
        deleteIfExists(storageUrl)
        throw e
      }

      try {
        // Make sure we've finished extracting EXIF metadata from the stream before trying to pull
        // values from it. At this point, storageStream has been completely consumed because the
        // file has been copied to the file store.
        exifThread.join()

        val fileMetadataWithExifValues =
            newFileMetadata.copy(
                capturedLocalTime =
                    newFileMetadata.capturedLocalTime ?: exifMetadata?.extractCapturedTime(),
                geolocation = newFileMetadata.geolocation ?: exifMetadata?.extractGeolocation(),
            )

        val fullMetadata =
            populateMetadata?.invoke(fileMetadataWithExifValues) ?: fileMetadataWithExifValues

        val filesRow =
            FilesRow(
                capturedLocalTime = fullMetadata.capturedLocalTime,
                contentType = fullMetadata.contentType,
                createdTime = clock.instant(),
                createdBy = currentUser().userId,
                fileName = fullMetadata.filename,
                geolocation = fullMetadata.geolocation,
                modifiedBy = currentUser().userId,
                modifiedTime = clock.instant(),
                size = fullMetadata.size,
                storageUrl = storageUrl,
            )

        dslContext.transaction { _ ->
          filesDao.insert(filesRow)

          if (fileType?.mimeType?.startsWith("video/") == true) {
            // This will cause a JobRunr job to be queued to send the file to Mux; if
            // insertChildRows throws an exception, the queued job will be rolled back and the file
            // will never be sent to Mux.
            eventPublisher.publishEvent(VideoFileUploadedEvent(filesRow.id!!))
          }

          val storedFile = StoredFile(filesRow.id!!, fullMetadata, fileType, exifMetadata)

          insertChildRows(storedFile)
        }

        filesRow.id!!
      } catch (e: Exception) {
        deleteIfExists(storageUrl)
        throw e
      }
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

  /**
   * Updates the modified-by and modified-time values of a file. This will typically be called after
   * updating file information in child tables.
   */
  fun touchFile(fileId: FileId) {
    with(FILES) {
      val rowsUpdated =
          dslContext
              .update(FILES)
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, clock.instant())
              .where(ID.eq(fileId))
              .execute()

      if (rowsUpdated != 1) {
        throw FileNotFoundException(fileId)
      }
    }
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

  /**
   * Information about a file that has just been stored in the file store. This is passed to the
   * `insertChildRows` function passed to [FileService.storeFile].
   */
  data class StoredFile(
      val fileId: FileId, // This should be first so it's easily accessible via destructuring
      /**
       * Terraware file metadata, populated with values from EXIF and/or the metadata population
       * callback.
       */
      val metadata: NewFileMetadata,
      val fileType: FileType? = null,
      /** Raw EXIF (or equivalent) metadata if the file has any. */
      val exifMetadata: Metadata? = null,
  )
}
