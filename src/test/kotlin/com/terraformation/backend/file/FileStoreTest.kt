package com.terraformation.backend.file

import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import kotlin.io.path.Path
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for FileStore implementations. Using the same test suite for all implementations verifies
 * that they all have the same caller-visible behavior.
 */
abstract class FileStoreTest {
  protected abstract var store: FileStore

  private val badUrl = URI("bogus://invalid/uri")

  @Test
  fun `delete removes file`() {
    val url = makeUrl()

    createFile(url)

    store.delete(url)

    assertFalse(fileExists(url), "File should not exist")
  }

  @Test
  fun `delete throws exception if file does not exist`() {
    assertThrows<NoSuchFileException> { store.delete(makeUrl()) }
  }

  @Test
  fun `delete throws exception if URL is invalid`() {
    assertThrows<InvalidStorageLocationException> { store.delete(badUrl) }
  }

  @Test
  fun `read returns file contents`() {
    val url = makeUrl()
    val contents = Random.nextBytes(100)

    createFile(url, contents)

    val readResult = store.read(url)

    assertEquals(contents.size.toLong(), readResult.size, "Size should be correct")
    assertArrayEquals(contents, readResult.readAllBytes(), "Content should be identical")
  }

  @Test
  fun `read throws exception if file does not exist`() {
    assertThrows<NoSuchFileException> { store.read(makeUrl()) }
  }

  @Test
  fun `read throws exception if URL is invalid`() {
    assertThrows<InvalidStorageLocationException> { store.read(badUrl) }
  }

  @Test
  fun `size returns file size`() {
    val url = makeUrl()
    val contents = Random.nextBytes(101)

    createFile(url, contents)

    val sizeResult = store.size(url)

    assertEquals(contents.size.toLong(), sizeResult)
  }

  @Test
  fun `size throws exception if file does not exist`() {
    assertThrows<NoSuchFileException> { store.size(makeUrl()) }
  }

  @Test
  fun `size throws exception if URL is invalid`() {
    assertThrows<InvalidStorageLocationException> { store.size(badUrl) }
  }

  @Test
  fun `write creates new file`() {
    val url = makeUrl()
    val bytesToWrite = Random.nextBytes(100)

    store.write(url, bytesToWrite.inputStream())

    val bytesWritten = readFile(url)
    assertArrayEquals(bytesToWrite, bytesWritten)
  }

  @Test
  fun `write throws exception if file already exists`() {
    val url = makeUrl()

    createFile(url)

    assertThrows<FileAlreadyExistsException> { store.write(url, Random.nextBytes(1).inputStream()) }
  }

  @Test
  fun `write throws exception if URL is invalid`() {
    assertThrows<InvalidStorageLocationException> {
      store.write(badUrl, Random.nextBytes(1).inputStream())
    }
  }

  @Test
  fun `canAccept does not accept bogus URLs`() {
    assertFalse(store.canAccept(badUrl))
  }

  @Test
  fun `canAccept accepts its own URLs`() {
    val url = store.getUrl(Path("foo/bar"))
    assertTrue(store.canAccept(url))
  }

  @Test
  fun `getPath and getUrl are idempotent`() {
    val path = Path("abc/def")
    val url = store.getUrl(path)

    assertEquals(path, store.getPath(url), "Path from URL")
  }

  protected abstract fun makeUrl(): URI

  protected abstract fun createFile(url: URI, content: ByteArray = Random.nextBytes(1))

  protected abstract fun fileExists(url: URI): Boolean

  protected abstract fun readFile(url: URI): ByteArray
}
