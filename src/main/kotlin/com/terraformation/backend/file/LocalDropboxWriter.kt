package com.terraformation.backend.file

import com.terraformation.backend.log.perClassLogger
import jakarta.annotation.Priority
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import kotlin.io.path.Path
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

private const val FOLDER = "/dropbox"

/**
 * Stores documents that would normally go to Dropbox in the local [FileStore] instead. Selected
 * when `terraware.dropbox.use-local-store` is true.
 *
 * Dropbox addresses files by path (`/folder/name`). This class stores the bytes under a storage URL
 * derived from a hash of the full Dropbox path, so every operation can recompute a file's storage
 * URL from its path with no in-memory state. As a result the mapping survives JVM restarts as long
 * as the underlying [FileStore] is persistent, and collisions are detected by checking whether the
 * hashed storage URL already exists.
 *
 * Because the storage layout is flat (the folder hierarchy is collapsed into the hash), folders
 * have no backing storage and [delete] only removes the single file at the given path; it cannot
 * enumerate and remove a folder's children. This is sufficient for the local store because callers
 * only ever delete individual files.
 */
@ConditionalOnProperty("terraware.dropbox.use-local-store", havingValue = "true")
@Named
@Priority(20) // RemoteDropboxWriter takes precedence when both beans are present.
class LocalDropboxWriter(private val fileStore: FileStore) : DropboxWriter {
  private val log = perClassLogger()

  override fun uploadFile(folderPath: String, name: String, inputStream: InputStream): String {
    var finalName = name
    var storageUrl = storageUrlFor("$folderPath/$finalName")
    var attempt = 1
    while (exists(storageUrl)) {
      finalName = suffixedName(name, attempt++)
      storageUrl = storageUrlFor("$folderPath/$finalName")
    }

    fileStore.write(storageUrl, inputStream)

    log.info("Stored local Dropbox file $folderPath/$finalName")
    return finalName
  }

  override fun createFolder(path: String) {
    // Folders are implicit; nothing to do.
  }

  override fun rename(oldPath: String, newPath: String) {
    val oldUrl = storageUrlFor(oldPath)
    val newUrl = storageUrlFor(newPath)
    if (newUrl != oldUrl) {
      fileStore.read(oldUrl).use { fileStore.write(newUrl, it) }
      fileStore.delete(oldUrl)
    }
  }

  override fun delete(path: String) {
    fileStore.delete(storageUrlFor(path))
  }

  override fun shareFile(path: String): URI {
    val url = storageUrlFor(path)
    if (!exists(url)) {
      throw NoSuchFileException(path)
    }
    return url
  }

  private fun exists(url: URI): Boolean =
      try {
        fileStore.size(url)
        true
      } catch (_: NoSuchFileException) {
        false
      }

  private fun storageUrlFor(path: String): URI {
    val filename = path.substringAfterLast('/')
    return fileStore.getUrl(Path("$FOLDER/${sha256Hex(path).take(16)}${extensionOf(filename)}"))
  }

  private fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(dot) else ""
  }

  private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
  }

  private fun suffixedName(name: String, attempt: Int): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) {
      "${name.substring(0, dot)} ($attempt)${name.substring(dot)}"
    } else {
      "$name ($attempt)"
    }
  }
}
