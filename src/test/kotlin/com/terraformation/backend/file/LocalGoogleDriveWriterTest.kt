package com.terraformation.backend.file

import jakarta.ws.rs.core.MediaType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

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

    org.junit.jupiter.api.assertThrows<java.nio.file.NoSuchFileException> {
      writer.downloadFile(fileId)
    }
  }
}
