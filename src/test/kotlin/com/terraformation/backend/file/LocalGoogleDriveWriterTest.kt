package com.terraformation.backend.file

import jakarta.ws.rs.core.MediaType
import java.nio.file.NoSuchFileException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LocalGoogleDriveWriterTest {
  private val fileStore = InMemoryFileStore()
  private val writer = LocalGoogleDriveWriter(fileStore)

  private val parentFolderId = "root-folder"

  @Test
  fun `uploadFile stores bytes and returns a downloadable file id`() {
    val fileId =
        writer.uploadFile(
            parentFolderId,
            "doc.txt",
            MediaType.TEXT_PLAIN,
            "hello".byteInputStream(),
        )

    assertArrayEquals(
        "hello".toByteArray(),
        writer.downloadFile(fileId).readAllBytes(),
        "downloaded bytes",
    )
    assertEquals(MediaType.TEXT_PLAIN, writer.getFileContentType(fileId), "content type")
  }

  @Test
  fun `findOrCreateFolders reuses an existing folder with the same name`() {
    val first = writer.findOrCreateFolders("drive", parentFolderId, listOf("Internal"))
    val second = writer.findOrCreateFolders("drive", parentFolderId, listOf("Internal"))

    assertEquals(first, second, "same folder id for the same name")
  }

  @Test
  fun `shareFile of a folder round-trips through getFileIdForFolderUrl`() {
    val folderId = writer.findOrCreateFolders("drive", parentFolderId, listOf("Internal"))

    val url = writer.shareFile(folderId)

    assertEquals(folderId, writer.getFileIdForFolderUrl(url), "folder id parsed from url")
  }

  @Test
  fun `uploadFile overwrites the existing file when fileId matches`() {
    val terrawareFileId = com.terraformation.backend.db.default_schema.FileId(1L)

    val firstId =
        writer.uploadFile(
            parentFolderId,
            "doc.txt",
            MediaType.TEXT_PLAIN,
            "v1".byteInputStream(),
            fileId = terrawareFileId,
        )
    val secondId =
        writer.uploadFile(
            parentFolderId,
            "doc.txt",
            MediaType.TEXT_PLAIN,
            "v2".byteInputStream(),
            fileId = terrawareFileId,
        )

    assertEquals(firstId, secondId, "same file id reused on overwrite")
    assertArrayEquals(
        "v2".toByteArray(),
        writer.downloadFile(secondId).readAllBytes(),
        "overwritten bytes",
    )
  }

  @Test
  fun `renameFile changes the name used for overwrite lookups`() {
    val fileId =
        writer.uploadFile(parentFolderId, "old.txt", MediaType.TEXT_PLAIN, "x".byteInputStream())

    writer.renameFile(fileId, "new.txt")

    // An overwriting upload targeting the new name finds and reuses the renamed file.
    val sameId =
        writer.uploadFile(parentFolderId, "new.txt", MediaType.TEXT_PLAIN, "y".byteInputStream())
    assertEquals(fileId, sameId, "overwrite by new name reuses the renamed file")

    // An overwriting upload targeting the old name no longer matches it.
    val otherId =
        writer.uploadFile(parentFolderId, "old.txt", MediaType.TEXT_PLAIN, "z".byteInputStream())
    assertNotEquals(fileId, otherId, "old name no longer matches")
  }

  @Test
  fun `deleteFile removes the stored bytes`() {
    val fileId =
        writer.uploadFile(parentFolderId, "doc.txt", MediaType.TEXT_PLAIN, "x".byteInputStream())

    writer.deleteFile(fileId)

    assertThrows<NoSuchFileException> { writer.downloadFile(fileId) }
  }

  @Test
  fun `metadata is reloaded by a new writer after a restart`() {
    val fileId =
        writer.uploadFile(
            parentFolderId,
            "doc.txt",
            MediaType.TEXT_PLAIN,
            "hello".byteInputStream(),
        )
    writer.renameFile(fileId, "renamed.txt")

    // Simulate a restart: a fresh writer over the same persistent file store.
    val restartedWriter = LocalGoogleDriveWriter(fileStore)

    assertArrayEquals(
        "hello".toByteArray(),
        restartedWriter.downloadFile(fileId).readAllBytes(),
        "downloaded bytes after restart",
    )
    assertEquals(
        MediaType.TEXT_PLAIN,
        restartedWriter.getFileContentType(fileId),
        "content type after restart",
    )

    // The reloaded path is correct, so an overwriting upload by the renamed name reuses the file.
    val sameId =
        restartedWriter.uploadFile(
            parentFolderId,
            "renamed.txt",
            MediaType.TEXT_PLAIN,
            "world".byteInputStream(),
        )
    assertEquals(fileId, sameId, "overwrite by renamed name reuses the reloaded file")
  }

  @Test
  fun `metadata reflects deletions after a restart`() {
    val fileId =
        writer.uploadFile(parentFolderId, "doc.txt", MediaType.TEXT_PLAIN, "x".byteInputStream())
    writer.deleteFile(fileId)

    val restartedWriter = LocalGoogleDriveWriter(fileStore)

    assertThrows<NoSuchFileException> { restartedWriter.downloadFile(fileId) }
  }

  @Test
  fun `deleteFile on a folder removes all contained files`() {
    val folderId = writer.findOrCreateFolders("drive", parentFolderId, listOf("Internal"))
    val fileId =
        writer.uploadFile(folderId, "doc.txt", MediaType.TEXT_PLAIN, "data".byteInputStream())
    val fileUrl = writer.shareFile(fileId)

    writer.deleteFile(folderId)

    fileStore.assertFileWasDeleted(fileUrl)
    assertThrows<NoSuchFileException> { writer.downloadFile(fileId) }
  }
}
