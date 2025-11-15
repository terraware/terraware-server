package com.terraformation.backend.file

import com.terraformation.backend.BeanTestDouble
import jakarta.annotation.Priority
import jakarta.inject.Named
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.relativeTo
import org.junit.jupiter.api.fail
import org.springframework.http.MediaType

@Named
@Priority(1) // Higher priority than the non-test implementations of FileStore
class InMemoryFileStore(private val pathGenerator: PathGenerator? = null) :
    FileStore, BeanTestDouble {
  private val threadState = ThreadLocal.withInitial { State() }

  private var counter: Int
    get() = threadState.get().counter
    set(value) {
      threadState.get().counter = value
    }

  private val deletedFiles
    get() = threadState.get().deletedFiles

  val errorFiles
    get() = threadState.get().errorFiles

  val files
    get() = threadState.get().files

  override fun resetState() {
    threadState.remove()
  }

  fun assertFileNotExists(
      url: URI,
      message: String = "$url was found in file store but shouldn't have been",
  ) {
    if (url in files) {
      fail(message)
    }
  }

  fun assertFileExists(url: URI) {
    if (url !in files) {
      fail("$url not found in file store")
    }
  }

  fun assertFileWasDeleted(url: URI) {
    if (url !in deletedFiles) {
      fail("$url was not deleted from file store")
    }
  }

  fun throwOnFile(url: URI) {
    errorFiles.add(url)
  }

  override fun delete(url: URI) {
    throwIfRequested(url)

    files.remove(url) ?: throw NoSuchFileException("$url")
    deletedFiles.add(url)
  }

  override fun read(url: URI): SizedInputStream {
    throwIfRequested(url)

    val bytes = getFile(url)
    return SizedInputStream(
        ByteArrayInputStream(bytes),
        bytes.size.toLong(),
        MediaType.APPLICATION_OCTET_STREAM,
    )
  }

  override fun size(url: URI): Long {
    throwIfRequested(url)
    return getFile(url).size.toLong()
  }

  override fun write(url: URI, contents: InputStream) {
    throwIfRequested(url)

    if (url in files) {
      throw FileAlreadyExistsException("$url")
    }

    files[url] = contents.readAllBytes()
  }

  override fun canAccept(url: URI): Boolean = true

  override fun getUrl(path: Path): URI {
    val relativePath = if (path.isAbsolute) path.relativeTo(path.root) else path
    return URI("file:///$relativePath")
  }

  override fun newUrl(timestamp: Instant, category: String, contentType: String): URI {
    return if (pathGenerator != null) {
      getUrl(pathGenerator.generatePath(timestamp, category, contentType))
    } else {
      URI("file:///$timestamp/$category/${counter++}")
    }
  }

  private fun getFile(url: URI) = files[url] ?: throw NoSuchFileException("$url")

  private fun throwIfRequested(url: URI) {
    if (url in errorFiles) {
      throw IOException("Simulated error")
    }
  }

  /**
   * Per-thread state of the file store. A test running in one thread shouldn't interact with
   * another test running in another thread.
   */
  private class State {
    var counter = 0
    val deletedFiles = mutableSetOf<URI>()
    val errorFiles = mutableSetOf<URI>()
    val files = mutableMapOf<URI, ByteArray>()
  }
}
