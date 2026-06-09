package com.terraformation.backend.file

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.model.ExistingFileMetadata
import com.terraformation.backend.log.perClassLogger
import jakarta.annotation.Priority
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import kotlin.io.path.Path
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Stores documents that would normally go to Google Drive in the local [FileStore] instead.
 * Selected when `terraware.google-drive.use-local-store` is true.
 *
 * File IDs are derived from a SHA-256 hash of the drive ID, parent folder ID, and filename, so
 * storage URLs are deterministic. File data persists across server restarts as long as the
 * underlying [FileStore] is persistent. Folder semantics are simplified: folders have no backing
 * storage and folder contents are not tracked, so operations like [renameFile] and [moveFile] are
 * no-ops.
 */
@ConditionalOnProperty("terraware.google-drive.use-local-store", havingValue = "true")
@Named
@Priority(20) // RemoteGoogleDriveWriter takes precedence when both beans are present.
class LocalGoogleDriveWriter(private val fileStore: FileStore) : GoogleDriveWriter {
  private val log = perClassLogger()

  override fun findOrCreateFolders(
      driveId: String,
      parentFolderId: String,
      names: List<String>,
  ): String =
      names.fold(parentFolderId) { parent, name -> sha256Hex("$driveId/$parent/$name").take(16) }

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
    val id = sha256Hex("$driveId/$parentFolderId/$filename").take(16) + extensionOf(filename)
    val storageUrl = storageUrlFor(id)
    try {
      fileStore.delete(storageUrl)
    } catch (_: NoSuchFileException) {
      // File doesn't exist yet; nothing to delete.
    }
    fileStore.write(storageUrl, inputStream)
    log.info("Stored local Google Drive file $filename as $id")
    return id
  }

  override fun getFileContentType(googleFileId: String): String? = null

  override fun downloadFile(googleFileId: String): InputStream =
      fileStore.read(storageUrlFor(googleFileId))

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
    // No-op: file is still accessible via its original ID.
  }

  override fun deleteFile(googleFileId: String) {
    try {
      fileStore.delete(storageUrlFor(googleFileId))
    } catch (_: NoSuchFileException) {
      // Folder ID or already deleted; nothing to do.
    }
  }

  override fun shareFile(googleFileId: String): URI {
    return try {
      fileStore.size(storageUrlFor(googleFileId))
      storageUrlFor(googleFileId)
    } catch (_: NoSuchFileException) {
      folderUrl(googleFileId)
    }
  }

  override fun moveFile(googleFileId: String, parentFileId: String) {
    // No-op: file is still accessible via its original ID.
  }

  private fun storageUrlFor(id: String): URI = fileStore.getUrl(Path("/google-drive/$id"))

  private fun folderUrl(folderId: String): URI =
      URI("https://drive.google.com/drive/folders/$folderId")

  private fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(dot) else ""
  }

  private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
  }

  companion object {
    private const val LOCAL_DRIVE_ID = "local-drive"
  }
}
