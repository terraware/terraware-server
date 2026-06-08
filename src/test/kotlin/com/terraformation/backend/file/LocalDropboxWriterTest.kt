package com.terraformation.backend.file

import java.nio.file.NoSuchFileException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LocalDropboxWriterTest {
  private val fileStore = InMemoryFileStore()
  private val writer = LocalDropboxWriter(fileStore)

  private val folder = "/Engineering/Tests"

  @Test
  fun `uploadFile stores bytes and returns the requested name`() {
    val name = writer.uploadFile(folder, "a doc.txt", "hello".byteInputStream())

    assertEquals("a doc.txt", name, "returned name")
    assertArrayEquals(
        "hello".toByteArray(),
        fileStore.read(writer.shareFile("$folder/a doc.txt")).readAllBytes(),
        "stored bytes",
    )
  }

  @Test
  fun `uploadFile autorenames on conflict`() {
    val first = writer.uploadFile(folder, "doc.txt", "a".byteInputStream())
    val second = writer.uploadFile(folder, "doc.txt", "b".byteInputStream())

    assertEquals("doc.txt", first, "first upload uses requested name")
    assertNotEquals(first, second, "second upload uses a new name")
    assertArrayEquals(
        "b".toByteArray(),
        fileStore.read(writer.shareFile("$folder/$second")).readAllBytes(),
        "second file bytes",
    )
  }

  @Test
  fun `rename moves stored bytes to the new path`() {
    writer.uploadFile(folder, "old.txt", "data".byteInputStream())

    writer.rename("$folder/old.txt", "$folder/new.txt")

    assertThrows<NoSuchFileException> { writer.shareFile("$folder/old.txt") }
    assertArrayEquals(
        "data".toByteArray(),
        fileStore.read(writer.shareFile("$folder/new.txt")).readAllBytes(),
        "moved bytes",
    )
  }

  @Test
  fun `delete removes stored bytes`() {
    writer.uploadFile(folder, "doc.txt", "x".byteInputStream())
    val url = writer.shareFile("$folder/doc.txt")

    writer.delete("$folder/doc.txt")

    fileStore.assertFileWasDeleted(url)
    assertThrows<NoSuchFileException> { writer.shareFile("$folder/doc.txt") }
  }

  @Test
  fun `delete folder removes all contained files`() {
    val fileUrl =
        writer.shareFile(
            writer.uploadFile(folder, "doc.txt", "data".byteInputStream()).let { "$folder/$it" }
        )

    writer.delete(folder)

    fileStore.assertFileWasDeleted(fileUrl)
    assertThrows<NoSuchFileException> { writer.shareFile("$folder/doc.txt") }
  }
}
