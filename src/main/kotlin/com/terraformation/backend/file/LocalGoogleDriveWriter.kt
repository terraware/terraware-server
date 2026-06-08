package com.terraformation.backend.file

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.model.ExistingFileMetadata
import com.terraformation.backend.log.perClassLogger
import jakarta.annotation.Priority
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import java.nio.file.NoSuchFileException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Stores documents that would normally go to Google Drive in the local [FileStore] instead.
 * Selected when `terraware.google-drive.use-local-store` is true.
 *
 * Google Drive identifies files and folders by opaque string IDs and exposes folders via URLs like
 * `https://drive.google.com/drive/folders/<id>`. This class emulates that model over [FileStore]:
 * generated UUID strings stand in for Google file IDs, file bytes live in the [FileStore], and an
 * in-memory map tracks the metadata needed for lookups, overwrites, renames, and moves. The map is
 * not persisted, which is acceptable for tests and dev usage.
 */
@ConditionalOnProperty("terraware.google-drive.use-local-store", havingValue = "true")
@Named
@Priority(20) // RemoteGoogleDriveWriter takes precedence when both beans are present.
class LocalGoogleDriveWriter(private val fileStore: FileStore) : GoogleDriveWriter {
  private val log = perClassLogger()

  private val entries = ConcurrentHashMap<String, Entry>()

  override fun findOrCreateFolders(
      driveId: String,
      parentFolderId: String,
      names: List<String>,
  ): String = names.fold(parentFolderId) { parent, name -> findOrCreateFolder(parent, name) }

  override fun uploadFile(
      parentFolderId: String,
      filename: String,
      contentType: String,
      inputStream: InputStream,
      driveId: String,
      description: String?,
      fileId: FileId?,
      inputStreamContentType: String,
      overwriteExistingFile: Boolean,
  ): String {
    val existingId =
        if (overwriteExistingFile) findFileId(parentFolderId, filename, fileId) else null
    val id = existingId ?: UUID.randomUUID().toString()
    val storageUrl = storageUrlFor(id)

    if (existingId != null) {
      fileStore.delete(storageUrl)
    }
    fileStore.write(storageUrl, inputStream)

    entries[id] =
        Entry(
            id = id,
            name = filename,
            parentId = parentFolderId,
            contentType = contentType,
            storageUrl = storageUrl,
            terrawareFileId = fileId,
            isFolder = false,
        )

    log.info("Stored local Google Drive file $filename as $id")
    return id
  }

  override fun getFileContentType(googleFileId: String): String? =
      entries[googleFileId]?.contentType

  override fun downloadFile(googleFileId: String): InputStream =
      fileStore.read(entry(googleFileId).storageUrl ?: throw NoSuchFileException(googleFileId))

  override fun copyFile(
      driveId: String,
      parentFolderId: String,
      metadata: ExistingFileMetadata,
      description: String?,
      name: String,
  ): String =
      fileStore.read(metadata.storageUrl).use { inputStream ->
        uploadFile(
            parentFolderId = parentFolderId,
            filename = name,
            contentType = metadata.contentType,
            inputStream = inputStream,
            driveId = driveId,
            description = description,
            fileId = metadata.id,
        )
      }

  override fun getDriveIdForFile(googleFileId: String): String = LOCAL_DRIVE_ID

  override fun getFileIdForFolderUrl(folderUrl: URI): String {
    if (folderUrl.host == "drive.google.com" && folderUrl.path.startsWith("/drive/folders/")) {
      return folderUrl.path.substringAfterLast('/')
    } else {
      throw IllegalArgumentException("$folderUrl does not appear to be a Google Drive folder URL")
    }
  }

  override fun renameFile(googleFileId: String, newName: String) {
    entries.computeIfPresent(googleFileId) { _, entry -> entry.copy(name = newName) }
  }

  override fun deleteFile(googleFileId: String) {
    val entry = entries.remove(googleFileId) ?: return
    entry.storageUrl?.let { fileStore.delete(it) }

    entries.values
        .filter { it.parentId == googleFileId }
        .forEach { child -> deleteFile(child.id) }
  }

  override fun shareFile(googleFileId: String): URI {
    val entry = entries[googleFileId]
    return if (entry != null && !entry.isFolder && entry.storageUrl != null) {
      entry.storageUrl
    } else {
      folderUrl(googleFileId)
    }
  }

  override fun moveFile(googleFileId: String, parentFileId: String) {
    entries.computeIfPresent(googleFileId) { _, entry -> entry.copy(parentId = parentFileId) }
  }

  private fun findOrCreateFolder(parentFolderId: String, name: String): String {
    val existing =
        entries.values.firstOrNull {
          it.isFolder && it.parentId == parentFolderId && it.name == name
        }
    if (existing != null) {
      return existing.id
    }

    val id = UUID.randomUUID().toString()
    entries[id] =
        Entry(
            id = id,
            name = name,
            parentId = parentFolderId,
            contentType = FOLDER_CONTENT_TYPE,
            storageUrl = null,
            terrawareFileId = null,
            isFolder = true,
        )
    return id
  }

  private fun findFileId(parentFolderId: String, filename: String, fileId: FileId?): String? =
      entries.values
          .firstOrNull {
            !it.isFolder &&
                it.parentId == parentFolderId &&
                it.name == filename &&
                (fileId == null || it.terrawareFileId == fileId)
          }
          ?.id

  private fun entry(googleFileId: String): Entry =
      entries[googleFileId] ?: throw NoSuchFileException(googleFileId)

  private fun storageUrlFor(id: String): URI = fileStore.getUrl(Path("/google-drive/$id"))

  private fun folderUrl(folderId: String): URI =
      URI("https://drive.google.com/drive/folders/$folderId")

  private data class Entry(
      val id: String,
      val name: String,
      val parentId: String,
      val contentType: String,
      val storageUrl: URI?,
      val terrawareFileId: FileId?,
      val isFolder: Boolean,
  )

  companion object {
    private const val LOCAL_DRIVE_ID = "local-drive"
    private const val FOLDER_CONTENT_TYPE = "application/vnd.google-apps.folder"
  }
}
