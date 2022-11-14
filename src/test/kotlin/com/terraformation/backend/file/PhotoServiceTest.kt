package com.terraformation.backend.file

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.PhotoNotFoundException
import com.terraformation.backend.db.default_schema.PhotoId
import com.terraformation.backend.db.default_schema.tables.pojos.PhotosRow
import com.terraformation.backend.file.model.PhotoMetadata
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
import java.time.Clock
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

class PhotoServiceTest : DatabaseTest(), RunsAsUser {
  private val clock: Clock = mockk()
  private val config: TerrawareServerConfig = mockk()
  private lateinit var fileStore: FileStore
  private lateinit var pathGenerator: PathGenerator
  private lateinit var photoService: PhotoService
  private val random: Random = mockk()
  private val thumbnailStore: ThumbnailStore = mockk()

  override val user: TerrawareUser = mockUser()

  private lateinit var photoPath: Path
  private lateinit var photoStorageUrl: URI
  private lateinit var tempDir: Path

  private val contentType = MediaType.IMAGE_JPEG_VALUE
  private val filename = "test-photo.jpg"
  private val uploadedTime = ZonedDateTime.of(2021, 2, 3, 4, 5, 6, 0, ZoneOffset.UTC).toInstant()
  private val metadata = PhotoMetadata(filename, contentType, 1L)

  @BeforeEach
  fun setUp() {
    tempDir = Files.createTempDirectory(javaClass.simpleName)

    every { clock.instant() } returns uploadedTime
    every { config.photoDir } returns tempDir
    every { config.photoIntermediateDepth } returns 3

    every { random.nextLong() } returns 0x0123456789abcdef
    pathGenerator = PathGenerator(random)
    fileStore = LocalFileStore(config, pathGenerator)

    val relativePath = Path("2021", "02", "03", "category", "040506-0123456789ABCDEF.jpg")

    photoPath = tempDir.resolve(relativePath)
    photoStorageUrl = URI("file:///${relativePath.invariantSeparatorsPathString}")

    photoService = PhotoService(dslContext, clock, fileStore, photosDao, thumbnailStore)

    insertUser()
  }

  @AfterEach
  fun deleteTemporaryDirectory() {
    assertTrue(tempDir.toFile().deleteRecursively(), "Deleting temporary directory")
  }

  @Test
  fun `storePhoto writes file and database row`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(10)
    var insertedChildRow = false

    val photoId =
        photoService.storePhoto(
            "category", photoData.inputStream(), photoData.size.toLong(), metadata) {
              insertedChildRow = true
            }

    val expectedPhoto =
        PhotosRow(
            contentType = contentType,
            fileName = filename,
            id = photoId,
            storageUrl = photoStorageUrl,
            size = photoData.size.toLong(),
            createdBy = user.userId,
            createdTime = uploadedTime,
            modifiedBy = user.userId,
            modifiedTime = uploadedTime)

    assertTrue(Files.exists(photoPath), "Photo file $photoPath exists")
    assertArrayEquals(photoData, Files.readAllBytes(photoPath), "File contents")

    assertTrue(insertedChildRow, "Called function to insert child row")

    val actualPhoto = photosDao.fetchOneById(photoId)!!
    assertEquals(expectedPhoto, actualPhoto)
  }

  @Test
  fun `storePhoto deletes file if database insert fails`() {
    assertThrows(DuplicateKeyException::class.java) {
      photoService.storePhoto("category", ByteArray(0).inputStream(), 0, metadata) {
        throw DuplicateKeyException("something failed, oh no")
      }
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
      photoService.storePhoto("category", badStream, 1000, metadata) {}
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storePhoto throws exception if directory cannot be created`() {
    // Directory creation will fail if a path element already exists and is not a directory.
    Files.createDirectories(photoPath.parent.parent)
    Files.createFile(photoPath.parent)

    assertThrows(IOException::class.java) {
      photoService.storePhoto("category", ByteArray(0).inputStream(), 0, metadata) {}
    }
  }

  @Test
  fun `readPhoto reads existing photo file`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(1000)

    val photoId =
        photoService.storePhoto(
            "category", photoData.inputStream(), photoData.size.toLong(), metadata) {}

    val stream = photoService.readPhoto(photoId)

    assertArrayEquals(photoData, stream.readAllBytes())
  }

  @Test
  fun `readPhoto throws exception on nonexistent file`() {
    assertThrows(PhotoNotFoundException::class.java) { photoService.readPhoto(PhotoId(123)) }
  }

  @Test
  fun `readPhoto returns thumbnail if photo dimensions are specified`() {
    val photoData = Random.nextBytes(10)
    val thumbnailData = Random.nextBytes(10)
    val thumbnailStream =
        SizedInputStream(ByteArrayInputStream(thumbnailData), thumbnailData.size.toLong())
    val width = 123
    val height = 456

    val photoId =
        photoService.storePhoto(
            "category", photoData.inputStream(), photoData.size.toLong(), metadata) {}

    every { thumbnailStore.getThumbnailData(any(), any(), any()) } returns thumbnailStream

    val stream = photoService.readPhoto(photoId, width, height)

    verify { thumbnailStore.getThumbnailData(photoId, width, height) }

    assertArrayEquals(thumbnailData, stream.readAllBytes())
  }

  @Test
  fun `deletePhoto deletes thumbnails and full-sized photo`() {
    val photoData = Random.nextBytes(10)

    every { thumbnailStore.deleteThumbnails(any()) } just Runs

    photoService.storePhoto(
        "category",
        photoData.inputStream(),
        photoData.size.toLong(),
        metadata.copy(filename = "1.jpg")) {}

    val expectedPhotos = photosDao.findAll()

    every { random.nextLong() } returns 2L
    val photoIdToDelete =
        photoService.storePhoto(
            "category",
            photoData.inputStream(),
            photoData.size.toLong(),
            metadata.copy(filename = "2.jpg")) {}

    val photoUrlToDelete = photosDao.fetchOneById(photoIdToDelete)!!.storageUrl!!

    var deleteChildRowsFunctionCalled = false
    photoService.deletePhoto(photoIdToDelete) { deleteChildRowsFunctionCalled = true }

    assertTrue(deleteChildRowsFunctionCalled, "Delete child rows callback should have been called")

    verify { thumbnailStore.deleteThumbnails(photoIdToDelete) }
    confirmVerified(thumbnailStore)

    assertThrows<NoSuchFileException>("$photoUrlToDelete should be deleted") {
      fileStore.size(photoUrlToDelete)
    }

    assertEquals(expectedPhotos, photosDao.findAll(), "Photos")
  }
}
