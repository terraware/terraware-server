package com.terraformation.backend.file

import com.terraformation.backend.config.TerrawareServerConfig
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.annotation.ManagedBean
import javax.annotation.Priority
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.relativeTo
import org.apache.commons.io.IOUtils
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/** Stores photos on the local filesystem. */
@ConditionalOnProperty("terraware.photo-dir", havingValue = "")
@ManagedBean
@Priority(2) // If both S3 and filesystem storage are configured, prefer S3.
class LocalFileStore(private val config: TerrawareServerConfig) : FileStore {
  override fun delete(path: Path) {
    getFullPath(path).deleteExisting()
  }

  override fun read(path: Path): SizedInputStream {
    val stream = getFullPath(path).inputStream()

    return SizedInputStream(stream, size(path))
  }

  override fun size(path: Path): Long {
    return getFullPath(path).fileSize()
  }

  override fun write(path: Path, contents: InputStream, size: Long) {
    // The file might be in a subdirectory that doesn't exist yet.
    val fullPath = getFullPath(path)
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

  private fun getFullPath(path: Path): Path {
    // Treat all input paths as relative so callers can't write to random places on the filesystem.
    val relativePath = if (path.isAbsolute) path.relativeTo(path.root) else path

    return config.photoDir?.resolve(relativePath)
        ?: throw IllegalArgumentException("No photo directory specified")
  }
}
