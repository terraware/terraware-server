package com.terraformation.backend.file

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.model.ExistingFileMetadata
import com.terraformation.backend.log.perClassLogger
import jakarta.annotation.Priority
import jakarta.inject.Named
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

private const val FOLDER = "/google-drive"

/**
 * Stores documents that would normally go to Google Drive in the local [FileStore] instead.
 * Selected when `terraware.google-drive.use-local-store` is true.
 *
 * Google Drive identifies files by opaque IDs, so this class returns generated UUIDs and keeps an
 * in-memory map from file ID to an [Entry] holding the file's logical path and content type. The
 * storage URL is derived from a hash of the path (including the filename's extension), so the bytes
 * survive JVM restarts as long as the underlying [FileStore] is persistent. The path and content
 * type are needed for overwrite lookups, renames, moves, and folder deletes.
 *
 * The map is persisted to a JSON metadata file at the top level folder in the [FileStore]. It is
 * loaded into memory at startup and rewritten whenever the map changes (uploads, renames, moves,
 * and deletes), so the ID-to-path mappings survive a server restart as long as the underlying
 * [FileStore] is persistent. The map is not shared live across instances, so concurrent
 * multi-instance use can produce stale lookups. Folder semantics are simplified: folders have no
 * backing storage and only their immediate file children are tracked.
 */
@ConditionalOnProperty("terraware.google-drive.use-local-store", havingValue = "true")
@Named
@Priority(20) // RemoteGoogleDriveWriter takes precedence when both beans are present.
class LocalGoogleDriveWriter(
    private val fileStore: FileStore,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) : GoogleDriveWriter {
  private val log = perClassLogger()

  private val entries = ConcurrentHashMap<String, Entry>()

  private val persistLock = Any()

  private val metadataUrl: URI = fileStore.getUrl(Path("$FOLDER/$METADATA_FILENAME"))

  init {
    loadEntries()
  }

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
    val path = "$parentFolderId/$filename"
    val existingId = if (overwriteExistingFile) findIdForPath(path) else null
    val id = existingId ?: UUID.randomUUID().toString()
    val storageUrl = storageUrlFor(path)

    if (existingId != null) {
      try {
        fileStore.delete(storageUrl)
      } catch (_: NoSuchFileException) {
        // Already gone; nothing to delete.
      }
    }
    fileStore.write(storageUrl, inputStream)

    entries[id] = Entry(path, contentType)
    persist()

    log.info("Stored local Google Drive file $filename as $id")
    return id
  }

  override fun getFileContentType(googleFileId: String): String? =
      entries[googleFileId]?.contentType

  override fun downloadFile(googleFileId: String): InputStream {
    val entry = entries[googleFileId] ?: throw NoSuchFileException(googleFileId)
    return fileStore.read(storageUrlFor(entry.path))
  }

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
    val entry = entries[googleFileId] ?: return
    relocate(googleFileId, entry.path.substringBeforeLast('/'), newName)
  }

  override fun moveFile(googleFileId: String, parentFileId: String) {
    val entry = entries[googleFileId] ?: return
    relocate(googleFileId, parentFileId, entry.path.substringAfterLast('/'))
  }

  override fun deleteFile(googleFileId: String) {
    if (entries.containsKey(googleFileId)) {
      removeFile(googleFileId)
    } else {
      // Treat the ID as a folder and delete everything stored directly beneath it.
      val prefix = "$googleFileId/"
      entries.filterValues { it.path.startsWith(prefix) }.keys.forEach { removeFile(it) }
    }
  }

  override fun shareFile(googleFileId: String): URI {
    val entry = entries[googleFileId] ?: return folderUrl(googleFileId)
    return storageUrlFor(entry.path)
  }

  private fun findIdForPath(path: String): String? =
      entries.entries.firstOrNull { it.value.path == path }?.key

  /** Moves a file's bytes to the storage URL for its new location and updates its [Entry]. */
  private fun relocate(id: String, newParentFolderId: String, newName: String) {
    val entry = entries[id] ?: return
    val newPath = "$newParentFolderId/$newName"
    val oldUrl = storageUrlFor(entry.path)
    val newUrl = storageUrlFor(newPath)
    if (newUrl != oldUrl) {
      fileStore.read(oldUrl).use { fileStore.write(newUrl, it) }
      fileStore.delete(oldUrl)
    }
    entries[id] = entry.copy(path = newPath)
    persist()
  }

  private fun removeFile(id: String) {
    entries.remove(id)?.let {
      persist()
      try {
        fileStore.delete(storageUrlFor(it.path))
      } catch (_: NoSuchFileException) {
        // Already gone; nothing to delete.
      }
    }
  }

  /** Loads the persisted metadata into [entries] at startup. Starts empty if no file exists yet. */
  private fun loadEntries() {
    val bytes =
        try {
          fileStore.read(metadataUrl).use { it.readAllBytes() }
        } catch (_: NoSuchFileException) {
          return
        }
    if (bytes.isEmpty()) {
      return
    }
    val loaded: Map<String, Entry> = objectMapper.readValue(bytes)
    entries.putAll(loaded)
    log.info("Loaded ${entries.size} local Google Drive metadata entries")
  }

  /** Rewrites the metadata file with the current contents of [entries]. */
  private fun persist() {
    synchronized(persistLock) {
      val bytes = objectMapper.writeValueAsBytes(HashMap(entries))
      try {
        fileStore.delete(metadataUrl)
      } catch (_: NoSuchFileException) {
        // No existing metadata file to replace.
      }
      fileStore.write(metadataUrl, ByteArrayInputStream(bytes))
    }
  }

  private fun storageUrlFor(path: String): URI {
    val filename = path.substringAfterLast('/')
    return fileStore.getUrl(Path("$FOLDER/${sha256Hex(path).take(16)}${extensionOf(filename)}"))
  }

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

  private data class Entry(val path: String, val contentType: String)

  companion object {
    private const val LOCAL_DRIVE_ID = "local-drive"
    private const val METADATA_FILENAME = "metadata.json"
  }
}
