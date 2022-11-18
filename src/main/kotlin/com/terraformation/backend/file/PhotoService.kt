package com.terraformation.backend.file

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.PhotoNotFoundException
import com.terraformation.backend.db.default_schema.PhotoId
import com.terraformation.backend.db.default_schema.tables.daos.PhotosDao
import com.terraformation.backend.db.default_schema.tables.pojos.PhotosRow
import com.terraformation.backend.db.default_schema.tables.references.PHOTOS
import com.terraformation.backend.file.model.PhotoMetadata
import com.terraformation.backend.log.perClassLogger
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext

/**
 * Manages storage of photos including metadata. In this implementation, image files are stored on
 * the filesystem and metadata in the database.
 */
@ManagedBean
class PhotoService(
    private val dslContext: DSLContext,
    private val clock: Clock,
    private val fileStore: FileStore,
    private val photosDao: PhotosDao,
    private val thumbnailStore: ThumbnailStore,
) {
  private val log = perClassLogger()

  @Throws(IOException::class)
  fun storePhoto(
      category: String,
      data: InputStream,
      size: Long,
      metadata: PhotoMetadata,
      insertChildRows: (PhotoId) -> Unit
  ): PhotoId {
    val photoUrl = fileStore.newUrl(clock.instant(), category, metadata.contentType)

    try {
      fileStore.write(photoUrl, data)

      val photosRow =
          PhotosRow(
              contentType = metadata.contentType,
              createdTime = clock.instant(),
              createdBy = currentUser().userId,
              fileName = metadata.filename,
              modifiedBy = currentUser().userId,
              modifiedTime = clock.instant(),
              size = size,
              storageUrl = photoUrl,
          )

      dslContext.transaction { _ ->
        photosDao.insert(photosRow)
        insertChildRows(photosRow.id!!)
      }

      return photosRow.id!!
    } catch (e: FileAlreadyExistsException) {
      // Don't delete the existing file
      throw e
    } catch (e: Exception) {
      try {
        fileStore.delete(photoUrl)
      } catch (ignore: NoSuchFileException) {
        // Swallow this; file is already deleted
      }
      throw e
    }
  }

  @Throws(IOException::class)
  fun readPhoto(
      photoId: PhotoId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    return if (maxWidth != null || maxHeight != null) {
      thumbnailStore.getThumbnailData(photoId, maxWidth, maxHeight)
    } else {
      val photosRow = photosDao.fetchOneById(photoId) ?: throw PhotoNotFoundException(photoId)
      fileStore.read(photosRow.storageUrl!!).withContentType(photosRow.contentType)
    }
  }

  /** Returns the photo's size in bytes. */
  @Throws(IOException::class)
  fun getPhotoFileSize(photoId: PhotoId): Long {
    val photoUrl = fetchUrl(photoId)
    return fileStore.size(photoUrl)
  }

  /**
   * Deletes a photo and its thumbnails.
   *
   * @param deleteChildRows Deletes any rows from child tables that refer to the photos table. This
   * is called in a transaction before the photos table row is deleted.
   */
  fun deletePhoto(photoId: PhotoId, deleteChildRows: () -> Unit) {
    val storageUrl = fetchUrl(photoId)
    thumbnailStore.deleteThumbnails(photoId)

    try {
      fileStore.delete(storageUrl)
    } catch (e: NoSuchFileException) {
      log.warn("Photo file $storageUrl was already deleted from file store")
    }

    dslContext.transaction { _ ->
      deleteChildRows()
      photosDao.deleteById(photoId)
    }
  }

  /**
   * Returns the storage URL of an existing photo.
   *
   * @throws PhotoNotFoundException There was no record of the photo.
   */
  private fun fetchUrl(photoId: PhotoId): URI {
    return dslContext
        .select(PHOTOS.STORAGE_URL)
        .from(PHOTOS)
        .where(PHOTOS.ID.eq(photoId))
        .fetchOne(PHOTOS.STORAGE_URL)
        ?: throw PhotoNotFoundException(photoId)
  }
}
