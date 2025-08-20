package com.terraformation.backend.file

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
import io.mockk.Runs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString
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
import org.springframework.web.reactive.function.UnsupportedMediaTypeException

class FileServiceTest : DatabaseTest(), RunsAsUser {
  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private lateinit var fileStore: FileStore
  private lateinit var pathGenerator: PathGenerator
  private lateinit var fileService: FileService
  private val random: Random = mockk()
  private val thumbnailStore: ThumbnailStore = mockk()

  override val user: TerrawareUser = mockUser()

  private lateinit var photoPath: Path
  private lateinit var photoStorageUrl: URI
  private lateinit var tempDir: Path

  private val contentType = MediaType.IMAGE_JPEG_VALUE
  private val filename = "test-photo.jpg"
  private val uploadedTime = ZonedDateTime.of(2021, 2, 3, 4, 5, 6, 0, ZoneOffset.UTC).toInstant()
  private val metadata = FileMetadata.of(contentType, filename, 1L)

  @BeforeEach
  fun setUp() {
    tempDir = Files.createTempDirectory(javaClass.simpleName)

    clock.instant = uploadedTime
    every { config.photoDir } returns tempDir
    every { config.keepInvalidUploads } returns false

    every { random.nextLong() } returns 0x0123456789abcdef
    pathGenerator = PathGenerator(random)
    fileStore = LocalFileStore(config, pathGenerator)

    val relativePath = Path("2021", "02", "03", "category", "040506-0123456789ABCDEF.jpg")

    photoPath = tempDir.resolve(relativePath)
    photoStorageUrl = URI("file:///${relativePath.invariantSeparatorsPathString}")

    fileService = FileService(dslContext, clock, config, filesDao, fileStore, thumbnailStore)
  }

  @AfterEach
  fun deleteTemporaryDirectory() {
    assertTrue(tempDir.toFile().deleteRecursively(), "Deleting temporary directory")
  }

  @Test
  fun `storeFile writes file and database row`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(10)
    val size = photoData.size.toLong()
    var insertedChildRow = false

    val fileId =
        fileService.storeFile("category", photoData.inputStream(), metadata.copy(size = size)) {
          insertedChildRow = true
        }

    val expectedPhoto =
        FilesRow(
            contentType = contentType,
            fileName = filename,
            id = fileId,
            storageUrl = photoStorageUrl,
            size = size,
            createdBy = user.userId,
            createdTime = uploadedTime,
            modifiedBy = user.userId,
            modifiedTime = uploadedTime,
        )

    assertTrue(Files.exists(photoPath), "Photo file $photoPath exists")
    assertArrayEquals(photoData, Files.readAllBytes(photoPath), "File contents")

    assertTrue(insertedChildRow, "Called function to insert child row")

    val actualPhoto = filesDao.fetchOneById(fileId)!!
    assertEquals(expectedPhoto, actualPhoto)
  }

  @Test
  fun `storeFile deletes file if database insert fails`() {
    assertThrows(DuplicateKeyException::class.java) {
      fileService.storeFile("category", ByteArray(0).inputStream(), metadata) {
        throw DuplicateKeyException("something failed, oh no")
      }
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storeFile deletes file if contents can't be read from input stream`() {
    val badStream =
        object : InputStream() {
          override fun read(): Int {
            throw SocketTimeoutException()
          }
        }

    assertThrows<SocketTimeoutException> {
      fileService.storeFile("category", badStream, metadata) {}
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storeFile deletes file if validation fails`() {
    assertThrows<UnsupportedMediaTypeException> {
      fileService.storeFile(
          "category",
          ByteArray(0).inputStream(),
          metadata,
          { throw UnsupportedMediaTypeException("validation failed") },
      ) {}
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storeFile keeps file if validation fails and keepInvalidUploads config is set`() {
    every { config.keepInvalidUploads } returns true

    assertThrows<UnsupportedMediaTypeException> {
      fileService.storeFile(
          "category",
          ByteArray(0).inputStream(),
          metadata,
          { throw UnsupportedMediaTypeException("validation failed") },
      ) {}
    }

    assertTrue(Files.exists(photoPath), "File should still exist")
  }

  @Test
  fun `storeFile throws exception if directory cannot be created`() {
    // Directory creation will fail if a path element already exists and is not a directory.
    Files.createDirectories(photoPath.parent.parent)
    Files.createFile(photoPath.parent)

    assertThrows(IOException::class.java) {
      fileService.storeFile("category", ByteArray(0).inputStream(), metadata) {}
    }
  }

  @Test
  fun `readFile reads existing photo file`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(1000)

    val fileId = fileService.storeFile("category", photoData.inputStream(), metadata) {}

    val stream = fileService.readFile(fileId)

    assertEquals(MediaType.parseMediaType(metadata.contentType), stream.contentType, "Content type")
    assertArrayEquals(photoData, stream.readAllBytes())
  }

  @Test
  fun `readFile throws exception on nonexistent file`() {
    assertThrows(FileNotFoundException::class.java) { fileService.readFile(FileId(123)) }
  }

  @Test
  fun `readFile returns thumbnail if photo dimensions are specified`() {
    val photoData = Random.nextBytes(10)
    val thumbnailData = Random.nextBytes(10)
    val thumbnailStream =
        SizedInputStream(ByteArrayInputStream(thumbnailData), thumbnailData.size.toLong())
    val width = 123
    val height = 456

    val fileId = fileService.storeFile("category", photoData.inputStream(), metadata) {}

    every { thumbnailStore.getThumbnailData(any(), any(), any()) } returns thumbnailStream

    val stream = fileService.readFile(fileId, width, height)

    verify { thumbnailStore.getThumbnailData(fileId, width, height) }

    assertArrayEquals(thumbnailData, stream.readAllBytes())
  }

  @Test
  fun `deleteFile deletes thumbnails and full-sized photo`() {
    val photoData = Random.nextBytes(10)

    every { thumbnailStore.deleteThumbnails(any()) } just Runs

    fileService.storeFile("category", photoData.inputStream(), metadata.copy(filename = "1.jpg")) {}

    val expectedPhotos = filesDao.findAll()

    every { random.nextLong() } returns 2L
    val fileIdToDelete =
        fileService.storeFile(
            "category",
            photoData.inputStream(),
            metadata.copy(filename = "2.jpg"),
        ) {}

    val photoUrlToDelete = filesDao.fetchOneById(fileIdToDelete)!!.storageUrl!!

    var deleteChildRowsFunctionCalled = false
    fileService.deleteFile(fileIdToDelete) { deleteChildRowsFunctionCalled = true }

    assertTrue(deleteChildRowsFunctionCalled, "Delete child rows callback should have been called")

    verify { thumbnailStore.deleteThumbnails(fileIdToDelete) }
    confirmVerified(thumbnailStore)

    assertThrows<NoSuchFileException>("$photoUrlToDelete should be deleted") {
      fileStore.size(photoUrlToDelete)
    }

    assertEquals(expectedPhotos, filesDao.findAll(), "Photos")
  }
}
