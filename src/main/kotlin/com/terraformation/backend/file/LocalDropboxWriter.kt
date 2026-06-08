package com.terraformation.backend.file

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
 * Stores documents that would normally go to Dropbox in the local [FileStore] instead. Selected
 * when `terraware.dropbox.use-local-store` is true.
 *
 * Dropbox addresses files by path (`/folder/name`). This class stores the bytes under opaque UUID
 * keys in the [FileStore] and keeps an in-memory map from Dropbox path to storage URL, so that
 * arbitrary filenames (including ones with spaces) are handled safely. The map is not persisted,
 * which is acceptable for tests and dev usage.
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
    val storageUrl = fileStore.getUrl(Path("/dropbox/${UUID.randomUUID()}"))
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

  private fun suffixedName(name: String, attempt: Int): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) {
      "${name.substring(0, dot)} ($attempt)${name.substring(dot)}"
    } else {
      "$name ($attempt)"
    }
  }
}
