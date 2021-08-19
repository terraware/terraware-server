package com.terraformation.backend.seedbank.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.file.LocalFileStore
import com.terraformation.backend.seedbank.model.PhotoMetadata
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.random.Random
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

internal class PhotoRepositoryTest : RunsAsUser {
  private val accessionPhotosDao: AccessionPhotosDao = mockk()
  private val accessionStore: AccessionStore = mockk()
  private val clock: Clock = mockk()
  private val config: TerrawareServerConfig = mockk()
  private val photoContentStore = LocalFileStore(config)
  private val repository =
      PhotoRepository(accessionPhotosDao, accessionStore, clock, config, photoContentStore)

  override val user: UserModel = mockk()

  private lateinit var photoPath: Path
  private lateinit var tempDir: Path

  private val accessionId = AccessionId(12345)
  private val accessionNumber = "ZYXWVUTSRQPO"
  private val capturedTime = Instant.ofEpochMilli(1000)
  private val contentType = MediaType.IMAGE_JPEG_VALUE
  private val facilityId = FacilityId(100)
  private val filename = "test-photo.jpg"
  private val latitude = BigDecimal("123.456")
  private val longitude = BigDecimal("876.5432")
  private val accuracy = 50
  private val uploadedTime = Instant.now()
  private val metadata =
      PhotoMetadata(filename, contentType, capturedTime, latitude, longitude, accuracy)

  @BeforeEach
  fun createTemporaryDirectory() {
    tempDir = Files.createTempDirectory(javaClass.simpleName)

    every { accessionStore.getIdByNumber(any(), any()) } throws AccessionNotFoundException("boom")
    every { accessionStore.getIdByNumber(facilityId, accessionNumber) } returns accessionId
    every { clock.instant() } returns uploadedTime
    every { config.photoDir } returns tempDir
    every { config.photoIntermediateDepth } returns 3

    justRun { accessionPhotosDao.insert(any<AccessionPhotosRow>()) }

    photoPath =
        tempDir
            .resolve("$facilityId")
            .resolve("${accessionNumber[0]}")
            .resolve("${accessionNumber[1]}")
            .resolve("${accessionNumber[2]}")
            .resolve(accessionNumber)
            .resolve(filename)

    every { user.canReadAccession(any(), any()) } returns true
    every { user.canUpdateAccession(any(), any()) } returns true
  }

  @AfterEach
  fun deleteTemporaryDirectory() {
    assertTrue(tempDir.toFile().deleteRecursively(), "Deleting temporary directory")
  }

  @Test
  fun `storePhoto writes file and database row`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(10)

    repository.storePhoto(
        facilityId, accessionNumber, photoData.inputStream(), photoData.size.toLong(), metadata)

    val expectedPojo =
        AccessionPhotosRow(
            null,
            accessionId,
            filename,
            uploadedTime,
            capturedTime,
            contentType,
            photoData.size,
            latitude,
            longitude,
            accuracy)

    assertTrue(Files.exists(photoPath), "Photo file exists")
    assertArrayEquals(photoData, Files.readAllBytes(photoPath), "File contents")

    verify { accessionPhotosDao.insert(expectedPojo) }
  }

  @Test
  fun `storePhoto deletes file if database insert fails`() {
    val exception = DuplicateKeyException("oops")

    every { accessionPhotosDao.insert(any<AccessionPhotosRow>()) } throws exception

    assertThrows(DuplicateKeyException::class.java) {
      repository.storePhoto(facilityId, accessionNumber, ByteArray(0).inputStream(), 0, metadata)
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storePhoto deletes file if contents can't be read from input stream`() {
    val badStream =
        object : InputStream() {
          override fun read(): Int {
            throw SocketTimeoutException()
          }
        }

    assertThrows<SocketTimeoutException> {
      repository.storePhoto(facilityId, accessionNumber, badStream, 1000, metadata)
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storePhoto throws exception if accession does not exist`() {
    val exception = DuplicateKeyException("oops")

    every { accessionPhotosDao.insert(any<AccessionPhotosRow>()) } throws exception

    assertThrows(AccessionNotFoundException::class.java) {
      repository.storePhoto(facilityId, "nonexistent", ByteArray(0).inputStream(), 0, metadata)
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storePhoto throws exception if user does not have permission to update accession`() {
    every { user.canUpdateAccession(accessionId, facilityId) } returns false

    assertThrows(AccessDeniedException::class.java) {
      repository.storePhoto(facilityId, accessionNumber, ByteArray(0).inputStream(), 0, metadata)
    }
  }

  @Test
  fun `storePhoto throws exception if directory cannot be created`() {
    // Directory creation will fail if a path element already exists and is not a directory.
    Files.createDirectories(photoPath.parent.parent)
    Files.createFile(photoPath.parent)

    assertThrows(IOException::class.java) {
      repository.storePhoto(facilityId, accessionNumber, ByteArray(0).inputStream(), 0, metadata)
    }
  }

  @Test
  fun `storePhoto does not insert database row if file exists`() {
    Files.createDirectories(photoPath.parent)
    Files.createFile(photoPath)

    every { accessionPhotosDao.insert(any<AccessionPhotosRow>()) } throws
        RuntimeException("Should not be called")

    assertThrows(FileAlreadyExistsException::class.java) {
      repository.storePhoto(facilityId, accessionNumber, ByteArray(0).inputStream(), 0, metadata)
    }

    verify(exactly = 0) { accessionPhotosDao.insert(any<AccessionPhotosRow>()) }

    assertTrue(Files.exists(photoPath), "Existing file should not be removed")
  }

  @Test
  fun `readPhoto reads existing photo file`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(1000)

    Files.createDirectories(photoPath.parent)
    Files.copy(photoData.inputStream(), photoPath)

    val stream = repository.readPhoto(facilityId, accessionNumber, filename)

    assertArrayEquals(photoData, stream.readAllBytes())
  }

  @Test
  fun `readPhoto throws exception on nonexistent file`() {
    assertThrows(NoSuchFileException::class.java) {
      repository.readPhoto(facilityId, accessionNumber, filename)
    }
  }

  @Test
  fun `readPhoto throws exception if user does not have permission to read accession`() {
    every { user.canReadAccession(accessionId, facilityId) } returns false

    assertThrows(AccessDeniedException::class.java) {
      repository.readPhoto(facilityId, accessionNumber, filename)
    }
  }

  @Test
  fun `getPhotoFileSize returns size of existing photo`() {
    val expectedSize = 17
    val photoData = ByteArray(expectedSize)

    Files.createDirectories(photoPath.parent)
    Files.copy(photoData.inputStream(), photoPath)

    assertEquals(
        expectedSize.toLong(), repository.getPhotoFileSize(facilityId, accessionNumber, filename))
  }

  @Test
  fun `getPhotoFileSize throws exception on nonexistent file`() {
    assertThrows(NoSuchFileException::class.java) {
      repository.getPhotoFileSize(facilityId, accessionNumber, filename)
    }
  }

  @Test
  fun `getPhotoFileSize throws exception if user does not have permission to read accession`() {
    every { user.canReadAccession(accessionId, facilityId) } returns false

    assertThrows(AccessDeniedException::class.java) {
      repository.getPhotoFileSize(facilityId, accessionNumber, filename)
    }
  }
}
