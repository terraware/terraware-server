package com.terraformation.backend.file

import com.terraformation.backend.log.perClassLogger
import jakarta.annotation.Priority
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Stores documents that would normally go to Dropbox in the local [FileStore] instead. Selected
 * when `terraware.dropbox.use-local-store` is true.
 *
 * Dropbox addresses files by path (`/folder/name`). This class stores the bytes under storage URLs
 * derived from a hash of the full Dropbox path and keeps an in-memory map from Dropbox path to
 * storage URL. Because the URLs are deterministic, file data will survive JVM restarts as long as
 * the underlying [FileStore] is persistent. The map is not persisted and is not shared across
 * instances, so rename, folder-delete, and shareFile will not work correctly when running multiple
 * server instances or after a restart.
 */
@ConditionalOnProperty("terraware.dropbox.use-local-store", havingValue = "true")
@Named
@Priority(20) // RemoteDropboxWriter takes precedence when both beans are present.
class LocalDropboxWriter(private val fileStore: FileStore) : DropboxWriter {
  private val log = perClassLogger()

  private val pathToUrl = ConcurrentHashMap<String, URI>()

  override fun uploadFile(folderPath: String, name: String, inputStream: InputStream): String {
    var finalName = name
    var attempt = 1
    while (pathToUrl.containsKey("$folderPath/$finalName")) {
      finalName = suffixedName(name, attempt++)
    }

    val fullPath = "$folderPath/$finalName"
    val storageUrl =
        fileStore.getUrl(Path("/dropbox/${sha256Hex(fullPath).take(16)}${extensionOf(finalName)}"))
    fileStore.write(storageUrl, inputStream)
    pathToUrl[fullPath] = storageUrl

    log.info("Stored local Dropbox file $fullPath")
    return finalName
  }

  override fun createFolder(path: String) {
    // Folders are implicit; nothing to do.
  }

  override fun rename(oldPath: String, newPath: String) {
    val url = pathToUrl.remove(oldPath) ?: throw NoSuchFileException(oldPath)
    pathToUrl[newPath] = url
  }

  override fun delete(path: String) {
    val url = pathToUrl.remove(path)
    url?.let { fileStore.delete(it) }

    val folderPrefix = "$path/"
    val children = pathToUrl.keys.filter { it.startsWith(folderPrefix) }
    children.forEach { childPath -> pathToUrl.remove(childPath)?.let { fileStore.delete(it) } }

    if (url == null && children.isEmpty()) {
      throw NoSuchFileException(path)
    }
  }

  override fun shareFile(path: String): URI = pathToUrl[path] ?: throw NoSuchFileException(path)

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
