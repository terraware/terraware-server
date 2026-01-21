package com.terraformation.backend.file

import com.terraformation.backend.config.TerrawareServerConfig
import jakarta.annotation.Priority
import jakarta.inject.Named
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.outputStream
import kotlin.io.path.relativeTo
import org.apache.commons.io.IOUtils
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Stores photos on the local filesystem.
 *
 * This supports `file:` URLs. Currently, the host part of the URL is ignored, and the path part is
 * used as the _relative_ path of the file within the storage directory. That is, if the storage
 * directory is `/storage` and a file URL is `file:///foo/bar`, the file's full path will be
 * `/storage/foo/bar`.
 */
@ConditionalOnProperty("terraware.photo-dir", havingValue = "")
@Named
@Priority(20) // If both S3 and filesystem storage are configured, prefer S3.
class LocalFileStore(
    private val config: TerrawareServerConfig,
    private val pathGenerator: PathGenerator,
) : FileStore {
  override fun delete(url: URI) {
    getFullPath(url).deleteExisting()
  }

  override fun read(url: URI): SizedInputStream {
    val stream = getFullPath(url).inputStream()

    return SizedInputStream(stream, size(url))
  }

  override fun size(url: URI): Long {
    return getFullPath(url).fileSize()
  }

  override fun write(url: URI, contents: InputStream) {
    // The file might be in a subdirectory that doesn't exist yet.
    val fullPath = getFullPath(url)
    val directory = fullPath.parent
    Files.createDirectories(directory)

    fullPath.outputStream(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
      try {
        IOUtils.copyLarge(contents, out)
      } catch (e: IOException) {
        // Don't leave a half-written file sitting around.
        fullPath.deleteIfExists()
        throw e
      }
    }
  }

  override fun canAccept(url: URI): Boolean {
    return url.scheme == "file" && url.path.length > 1 && url.path[0] == '/'
  }

  override fun getUrl(path: Path): URI {
    val relativePath = if (path.isAbsolute) path.relativeTo(path.root) else path
    return URI("file:///${relativePath.invariantSeparatorsPathString}")
  }

  override fun getPath(url: URI): Path {
    return Path(url.path.trimStart('/'))
  }

  override fun newUrl(timestamp: Instant, category: String, contentType: String): URI {
    return getUrl(pathGenerator.generatePath(timestamp, category, contentType))
  }

  private fun getFullPath(url: URI): Path {
    if (!canAccept(url)) {
      throw InvalidStorageLocationException(url)
    }

    // Treat all input paths as relative so callers can't write to random places on the filesystem.
    // If the path contains `..` elements, resolve them before appending the relative path to the
    // storage directory, so you can't use `file:///../` to escape from the storage directory.
    val absolutePath = Path(url.path).toAbsolutePath()
    val relativePath = absolutePath.relativeTo(absolutePath.root)

    return config.photoDir?.resolve(relativePath)
        ?: throw IllegalArgumentException("No photo directory specified")
  }
}
