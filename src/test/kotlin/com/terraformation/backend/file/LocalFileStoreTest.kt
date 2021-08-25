package com.terraformation.backend.file

import com.terraformation.backend.config.TerrawareServerConfig
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import org.junit.jupiter.api.BeforeEach
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

  override fun createFile(path: Path, content: ByteArray) {
    val fullPath = tempDir.resolve(path)
    fullPath.parent?.createDirectories()
    fullPath.outputStream().use { stream -> stream.write(content) }
  }

  override fun fileExists(path: Path): Boolean {
    return tempDir.resolve(path).exists()
  }

  override fun readFile(path: Path): ByteArray {
    val fullPath = tempDir.resolve(path)
    return fullPath.inputStream().readAllBytes()
  }
}
