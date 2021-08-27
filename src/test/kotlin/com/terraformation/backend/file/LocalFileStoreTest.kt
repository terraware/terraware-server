package com.terraformation.backend.file

import com.terraformation.backend.config.TerrawareServerConfig
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.random.Random
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class LocalFileStoreTest : FileStoreTest() {
  @TempDir lateinit var tempDir: Path

  val config: TerrawareServerConfig = mockk()

  override lateinit var store: FileStore

  @BeforeEach
  fun setUp() {
    every { config.photoDir } returns tempDir

    store = LocalFileStore(config)
  }

  @Test
  fun `cannot escape storage directory with relative paths in URLs`() {
    tempDir.resolve("fileInParentDir").outputStream().use { stream -> stream.write(0) }
    tempDir.resolve("subdir").createDirectories()

    every { config.photoDir } returns tempDir.resolve("subdir")
    store = LocalFileStore(config)

    assertThrows<NoSuchFileException> { store.read(URI("file:///../fileInParentDir")) }
  }

  override fun createFile(url: URI, content: ByteArray) {
    val fullPath = tempDir.resolve(relativePath(url))
    fullPath.parent?.createDirectories()
    fullPath.outputStream().use { stream -> stream.write(content) }
  }

  override fun fileExists(url: URI): Boolean {
    return tempDir.resolve(relativePath(url)).exists()
  }

  override fun readFile(url: URI): ByteArray {
    val fullPath = tempDir.resolve(relativePath(url))
    return fullPath.inputStream().readAllBytes()
  }

  override fun makeUrl(): URI {
    return URI("file:///${Random.nextInt()}")
  }

  private fun relativePath(url: URI): Path {
    return Path(url.path.substring(1))
  }
}
