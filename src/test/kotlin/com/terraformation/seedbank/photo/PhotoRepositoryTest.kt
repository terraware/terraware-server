package com.terraformation.seedbank.photo

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.AccessionNotFoundException
import com.terraformation.seedbank.db.AccessionStore
import com.terraformation.seedbank.db.tables.daos.AccessionPhotoDao
import com.terraformation.seedbank.db.tables.pojos.AccessionPhoto
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.math.BigDecimal
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
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.MediaType

internal class PhotoRepositoryTest {
  private val accessionPhotoDao: AccessionPhotoDao = mockk()
  private val accessionStore: AccessionStore = mockk()
  private val clock: Clock = mockk()
  private val config: TerrawareServerConfig = mockk()
  private val repository = PhotoRepository(config, accessionPhotoDao, accessionStore, clock)

  private lateinit var tempDir: Path

  private val accessionId = 12345L
  private val accessionNumber = "ZYXWVUTSRQPO"
  private val capturedTime = Instant.ofEpochMilli(1000)
  private val contentType = MediaType.IMAGE_JPEG_VALUE
  private val filename = "test-photo.jpg"
  private val latitude = BigDecimal("123.456")
  private val longitude = BigDecimal("876.5432")
  private val accuracy = 50
  private val uploadedTime = Instant.now()
  private val metadata =
      PhotoMetadata(filename, contentType, capturedTime, latitude, longitude, accuracy)

  private lateinit var photoPath: Path

  @BeforeEach
  fun createTemporaryDirectory() {
    tempDir = Files.createTempDirectory(javaClass.simpleName)

    every { accessionStore.getIdByNumber(any()) } throws AccessionNotFoundException("boom")
    every { accessionStore.getIdByNumber(accessionNumber) } returns accessionId
    every { clock.instant() } returns uploadedTime
    every { config.photoDir } returns tempDir
    every { config.photoIntermediateDepth } returns 3

    justRun { accessionPhotoDao.insert(any<AccessionPhoto>()) }

    photoPath =
        tempDir
            .resolve("${accessionNumber[0]}")
            .resolve("${accessionNumber[1]}")
            .resolve("${accessionNumber[2]}")
            .resolve(accessionNumber)
            .resolve(filename)
  }

  @AfterEach
  fun deleteTemporaryDirectory() {
    assertTrue(tempDir.toFile().deleteRecursively(), "Deleting temporary directory")
  }

  @Test
  fun `storePhoto writes file and database row`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(10)

    repository.storePhoto(accessionNumber, photoData.inputStream(), metadata)

    val expectedPojo =
        AccessionPhoto(
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

    verify { accessionPhotoDao.insert(expectedPojo) }
  }

  @Test
  fun `storePhoto deletes file if database insert fails`() {
    val exception = DuplicateKeyException("oops")

    every { accessionPhotoDao.insert(any<AccessionPhoto>()) } throws exception

    assertThrows(DuplicateKeyException::class.java) {
      repository.storePhoto(accessionNumber, ByteArray(0).inputStream(), metadata)
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storePhoto throws exception if accession does not exist`() {
    val exception = DuplicateKeyException("oops")

    every { accessionPhotoDao.insert(any<AccessionPhoto>()) } throws exception

    assertThrows(AccessionNotFoundException::class.java) {
      repository.storePhoto("nonexistent", ByteArray(0).inputStream(), metadata)
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storePhoto throws exception if directory cannot be created`() {
    // Directory creation will fail if a path element already exists and is not a directory.
    Files.createDirectories(photoPath.parent.parent)
    Files.createFile(photoPath.parent)

    assertThrows(IOException::class.java) {
      repository.storePhoto(accessionNumber, ByteArray(0).inputStream(), metadata)
    }
  }

  @Test
  fun `storePhoto does not insert database row if file exists`() {
    Files.createDirectories(photoPath.parent)
    Files.createFile(photoPath)

    every { accessionPhotoDao.insert(any<AccessionPhoto>()) } throws
        RuntimeException("Should not be called")

    assertThrows(FileAlreadyExistsException::class.java) {
      repository.storePhoto(accessionNumber, ByteArray(0).inputStream(), metadata)
    }

    verify(exactly = 0) { accessionPhotoDao.insert(any<AccessionPhoto>()) }

    assertTrue(Files.exists(photoPath), "Existing file should not be removed")
  }

  @Test
  fun `readPhoto reads existing photo file`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(1000)

    Files.createDirectories(photoPath.parent)
    Files.copy(photoData.inputStream(), photoPath)

    val stream = repository.readPhoto(accessionNumber, filename)

    assertArrayEquals(photoData, stream.readAllBytes())
  }

  @Test
  fun `readPhoto throws exception on nonexistent file`() {
    assertThrows(NoSuchFileException::class.java) {
      repository.readPhoto(accessionNumber, filename)
    }
  }

  @Test
  fun `getPhotoFileSize returns size of existing photo`() {
    val expectedSize = 17
    val photoData = ByteArray(expectedSize)

    Files.createDirectories(photoPath.parent)
    Files.copy(photoData.inputStream(), photoPath)

    assertEquals(expectedSize.toLong(), repository.getPhotoFileSize(accessionNumber, filename))
  }

  @Test
  fun `getPhotoFileSize throws exception on nonexistent file`() {
    assertThrows(NoSuchFileException::class.java) {
      repository.getPhotoFileSize(accessionNumber, filename)
    }
  }
}
