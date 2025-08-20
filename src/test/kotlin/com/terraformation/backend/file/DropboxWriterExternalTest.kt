package com.terraformation.backend.file

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.dummyTerrawareServerConfig
import com.terraformation.backend.getEnvOrSkipTest
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DropboxWriterExternalTest {
  private lateinit var writer: DropboxWriter

  /** Folder in which the temporary folders for test runs will be created. */
  private val parentFolder = "/Engineering/Automated Tests"

  private var scratchFolderCreated: Boolean = false
  private val scratchFolder: String by lazy {
    val path = "$parentFolder/${javaClass.simpleName}-${UUID.randomUUID()}"
    writer.createFolder(path)
    scratchFolderCreated = true
    path
  }

  @BeforeEach
  fun setUp() {
    val appKey = getEnvOrSkipTest("TERRAWARE_DROPBOX_APPKEY")
    val appSecret = getEnvOrSkipTest("TERRAWARE_DROPBOX_APPSECRET")
    val refreshToken = getEnvOrSkipTest("TERRAWARE_DROPBOX_REFRESHTOKEN")

    val config =
        dummyTerrawareServerConfig(
            dropbox =
                TerrawareServerConfig.DropboxConfig(
                    enabled = true,
                    appKey = appKey,
                    appSecret = appSecret,
                    refreshToken = refreshToken,
                )
        )

    writer = DropboxWriter(config)
  }

  @AfterEach
  fun deleteScratchFolder() {
    if (scratchFolderCreated) {
      writer.delete(scratchFolder)
    }
  }

  @Test
  fun `can create folders`() {
    // Lazy evaluation will create the folder; this is really checking whether the lazy init
    // function throws an exception.
    assertNotNull(scratchFolder)
  }

  @Test
  fun `uploading file with same name as existing file uses new filename`() {
    val name = "test file"

    val uploadedName1 = writer.uploadFile(scratchFolder, name, "a".byteInputStream())
    val uploadedName2 = writer.uploadFile(scratchFolder, name, "b".byteInputStream())

    assertEquals(name, uploadedName1, "First upload should have used requested name")
    assertNotEquals(uploadedName1, uploadedName2, "Second upload should have used new name")
  }

  @Test
  fun `implicitly creates missing folders`() {
    val name = "test file"

    val uploadedName = writer.uploadFile("$scratchFolder/subdir", name, "a".byteInputStream())

    assertEquals(name, uploadedName)
  }

  @Test
  fun `can generate shared link for file`() {
    val name = "share test"

    writer.uploadFile(scratchFolder, name, "a".byteInputStream())

    // Generate shared link twice to test "link already exists" logic which kicks in when the same
    // file is shared repeatedly while a valid link still exists.
    assertNotNull(writer.shareFile("$scratchFolder/$name"))
    assertNotNull(writer.shareFile("$scratchFolder/$name"))
  }
}
