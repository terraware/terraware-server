package com.terraformation.backend.file

import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for FileStore implementations. Using the same test suite for all implementations verifies
 * that they all have the same caller-visible behavior.
 */
abstract class FileStoreTest {
  protected abstract var store: FileStore

  @Test
  fun `delete removes file`() {
    val path = makePath()

    createFile(path)

    store.delete(path)

    assertFalse(fileExists(path), "File should not exist")
  }

  @Test
  fun `delete throws exception if file does not exist`() {
    assertThrows<NoSuchFileException> { store.delete(makePath()) }
  }

  @Test
  fun `read returns file contents`() {
    val path = makePath()
    val contents = Random.nextBytes(100)

    createFile(path, contents)

    val readResult = store.read(path)

    assertEquals(contents.size.toLong(), readResult.size, "Size should be correct")
    assertArrayEquals(contents, readResult.readAllBytes(), "Content should be identical")
  }

  @Test
  fun `read throws exception if file does not exist`() {
    assertThrows<NoSuchFileException> { store.read(makePath()) }
  }

  @Test
  fun `size returns file size`() {
    val path = makePath()
    val contents = Random.nextBytes(101)

    createFile(path, contents)

    val sizeResult = store.size(path)

    assertEquals(contents.size.toLong(), sizeResult)
  }

  @Test
  fun `size throws exception if file does not exist`() {
    assertThrows<NoSuchFileException> { store.size(makePath()) }
  }

  @Test
  fun `store creates new file`() {
    val path = makePath()
    val bytesToWrite = Random.nextBytes(100)

    store.write(path, bytesToWrite.inputStream(), bytesToWrite.size.toLong())

    val bytesWritten = readFile(path)
    assertArrayEquals(bytesToWrite, bytesWritten)
  }

  @Test
  fun `store throws exception if file already exists`() {
    val path = makePath()

    createFile(path)

    assertThrows<FileAlreadyExistsException> {
      store.write(path, Random.nextBytes(1).inputStream(), 1)
    }
  }

  protected open fun makePath(): Path {
    return Path("${Random.nextInt()}")
  }

  protected abstract fun createFile(path: Path, content: ByteArray = Random.nextBytes(1))

  protected abstract fun fileExists(path: Path): Boolean

  protected abstract fun readFile(path: Path): ByteArray
}
