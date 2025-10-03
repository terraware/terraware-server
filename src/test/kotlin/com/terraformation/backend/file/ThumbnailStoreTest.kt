package com.terraformation.backend.file

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.pojos.ThumbnailsRow
import com.terraformation.backend.db.default_schema.tables.references.THUMBNAILS
import com.terraformation.backend.mockUser
import com.terraformation.backend.onePixelPng
import com.terraformation.backend.util.ImageUtils
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType

internal class ThumbnailStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)!!
  private val fileStore: FileStore = mockk()
  private val imageUtils: ImageUtils = spyk(ImageUtils(fileStore))

  private lateinit var store: ThumbnailStore

  private val photoStorageUrl = URI("file:///a/b/c/original.jpg")

  private lateinit var fileId: FileId

  @BeforeEach
  fun setUp() {
    store = ThumbnailStore(clock, dslContext, fileStore, filesDao, thumbnailsDao, imageUtils)

    val filesRow =
        FilesRow(
            contentType = MediaType.IMAGE_JPEG_VALUE,
            createdBy = user.userId,
            createdTime = clock.instant(),
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            size = 1,
            fileName = "test.jpg",
            storageUrl = photoStorageUrl,
        )
    filesDao.insert(filesRow)
    fileId = filesRow.id!!

    every { fileStore.delete(any()) } throws NoSuchFileException("Not found")
    every { fileStore.getUrl(any()) } answers { URI("file://${firstArg<Path>()}") }
    every { fileStore.read(any()) } throws NoSuchFileException("Not found")
    every { fileStore.read(photoStorageUrl) } answers
        {
          SizedInputStream(ByteArrayInputStream(photoJpegData), photoJpegData.size.toLong())
        }
    every { imageUtils.getOrientation(any()) } returns 1
  }

  private fun insertThumbnail(
      width: Int,
      height: Int,
      imageData: ByteArray = Random.nextBytes(10),
      storageUrl: URI = URI("file:///a/b/c/thumb/original-${width}x$height.jpg"),
  ): ThumbnailsRow {
    val thumbnailsRow =
        ThumbnailsRow(
            fileId = fileId,
            width = width,
            height = height,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            createdTime = clock.instant(),
            size = imageData.size,
            storageUrl = storageUrl,
        )
    thumbnailsDao.insert(thumbnailsRow)

    every { fileStore.delete(storageUrl) } just Runs
    every { fileStore.read(storageUrl) } answers
        {
          SizedInputStream(ByteArrayInputStream(imageData), imageData.size.toLong())
        }

    return thumbnailsRow
  }

  @Nested
  inner class CanGenerateThumbnails {
    @Test
    fun `returns true for common image types but not video types`() {
      val expected =
          mapOf(
              "application/octet-stream" to false,
              "image/jpeg" to true,
              "image/png" to true,
              "image/tiff" to true,
              "video/mp4" to false,
              "video/quicktime" to false,
          )
      val actual = expected.keys.associateWith { store.canGenerateThumbnails(it) }

      assertEquals(expected, actual)
    }

    @Test
    fun `ignores MIME type suffixes`() {
      assertTrue(store.canGenerateThumbnails("image/jpeg; foo=bar"))
    }
  }

  @Nested
  inner class GetThumbnailData {
    @Test
    fun `returns existing thumbnail of requested size`() {
      val expected = Random.nextBytes(10)

      insertThumbnail(80, 70, expected)

      val actual = store.getThumbnailData(fileId, 80, 70)

      assertArrayEquals(expected, actual.readAllBytes())
    }

    @Test
    fun `returns existing thumbnail if only width is specified`() {
      val expected = Random.nextBytes(10)

      insertThumbnail(80, 70, expected)

      val actual = store.getThumbnailData(fileId, 80, null)

      assertArrayEquals(expected, actual.readAllBytes())
    }

    @Test
    fun `returns existing thumbnail if only height is specified`() {
      val expected = Random.nextBytes(10)

      insertThumbnail(80, 70, expected)

      val actual = store.getThumbnailData(fileId, null, 70)

      assertArrayEquals(expected, actual.readAllBytes())
    }

    @Test
    fun `requires width and height to be within valid range`() {
      val testCases =
          mapOf(
              "no dimensions" to listOf(null, null),
              "zero width" to listOf(0, 500),
              "zero height" to listOf(500, 0),
              "negative width" to listOf(-1, 500),
              "negative height" to listOf(500, -1),
              "huge width" to listOf(100000, 500),
              "huge height" to listOf(500, 100000),
          )

      testCases.forEach { description, (width, height) ->
        assertThrows<IllegalArgumentException>(description) {
          store.getThumbnailData(fileId, width, height)
        }
      }
    }

    @Test
    fun `returns shorter thumbnail that matches requested width`() {
      val expected = Random.nextBytes(10)
      val width = 80
      val height = 70

      insertThumbnail(width, height - 1, expected)

      val actual = store.getThumbnailData(fileId, width, height)

      assertArrayEquals(expected, actual.readAllBytes())
    }

    @Test
    fun `returns narrower thumbnail that matches requested height`() {
      val expected = Random.nextBytes(10)
      val width = 80
      val height = 70

      insertThumbnail(width - 1, height, expected)

      val actual = store.getThumbnailData(fileId, width, height)

      assertArrayEquals(expected, actual.readAllBytes())
    }

    @Test
    fun `generates new thumbnail if none exists with requested size`() {
      val width = photoWidth / 10
      val height = photoHeight / 10

      insertThumbnail(width, height + 1)
      insertThumbnail(width + 1, height)
      insertThumbnail(width - 1, height - 1)

      val thumbUrlSlot: CapturingSlot<URI> = slot()
      val streamSlot: CapturingSlot<InputStream> = slot()
      justRun { fileStore.write(capture(thumbUrlSlot), capture(streamSlot)) }

      val actual = store.getThumbnailData(fileId, width, height)

      verify(exactly = 1) { fileStore.write(any(), any()) }

      assertEquals(
          URI("file:///a/b/c/thumb/original-${width}x$height.jpg"),
          thumbUrlSlot.captured,
          "Should have derived thumbnail URL from original photo URL",
      )
      assertArrayEquals(
          actual.readAllBytes(),
          streamSlot.captured.readAllBytes(),
          "Should have written same image to file store that was returned to caller",
      )
    }

    @Test
    fun `generates thumbnails as valid JPEG files`() {
      val width = photoWidth / 10
      val height = photoHeight / 10

      justRun { fileStore.write(any(), any()) }

      val actual = store.getThumbnailData(fileId, width, height)
      val actualData = actual.readAllBytes()

      val imageReader = ImageIO.getImageReadersByFormatName("JPEG").next()
      imageReader.input = MemoryCacheImageInputStream(ByteArrayInputStream(actualData))
      val thumbnailImage = imageReader.read(0)

      assertEquals(width, thumbnailImage.width, "Thumbnail width")
      assertEquals(height, thumbnailImage.height, "Thumbnail height")
    }

    @Test
    fun `generates JPEG thumbnails of PNG files`() {
      val width = photoWidth / 10
      val height = photoHeight / 10

      every { fileStore.read(photoStorageUrl) } answers
          {
            SizedInputStream(ByteArrayInputStream(photoPngData), photoPngData.size.toLong())
          }
      justRun { fileStore.write(any(), any()) }

      filesDao.update(filesDao.fetchOneById(fileId)!!.copy(contentType = MediaType.IMAGE_PNG_VALUE))

      val actual = store.getThumbnailData(fileId, width, height)
      val actualData = actual.readAllBytes()

      val imageReader = ImageIO.getImageReadersByFormatName("JPEG").next()
      imageReader.input = MemoryCacheImageInputStream(ByteArrayInputStream(actualData))
      val thumbnailImage = imageReader.read(0)

      assertEquals(width, thumbnailImage.width, "Thumbnail width")
      assertEquals(height, thumbnailImage.height, "Thumbnail height")
    }

    @Test
    fun `generates thumbnails above minimum high-quality dimensions`() {
      justRun { fileStore.write(any(), any()) }

      val actual =
          store.getThumbnailData(fileId, store.minSizeForHighQuality, store.minSizeForHighQuality)
      val actualData = actual.readAllBytes()

      val imageReader = ImageIO.getImageReadersByFormatName("JPEG").next()
      imageReader.input = MemoryCacheImageInputStream(ByteArrayInputStream(actualData))
      val thumbnailImage = imageReader.read(0)

      assertEquals(store.minSizeForHighQuality, thumbnailImage.width, "Thumbnail width")
    }

    @Test
    fun `inserts database row for existing thumbnail if file already exists`() {
      val width = photoWidth / 10
      val height = photoHeight / 10

      every { fileStore.write(any(), any()) } throws FileAlreadyExistsException("Exists")

      val actual = store.getThumbnailData(fileId, width, height)

      assertEquals(
          listOf(
              ThumbnailsRow(
                  fileId = fileId,
                  width = width,
                  height = height,
                  contentType = MediaType.IMAGE_JPEG_VALUE,
                  createdTime = Instant.EPOCH,
                  size = actual.size.toInt(),
                  storageUrl = URI("file:///a/b/c/thumb/original-${width}x$height.jpg"),
              )
          ),
          thumbnailsDao.findAll().map { it.copy(id = null) },
      )
    }

    @Test
    fun `regenerates thumbnail if file is missing`() {
      val width = photoWidth / 10
      val height = photoHeight / 10

      val existingRow = insertThumbnail(width, height)

      every { fileStore.read(existingRow.storageUrl!!) } throws NoSuchFileException("Nope")
      justRun { fileStore.write(any(), any()) }

      val actual = store.getThumbnailData(fileId, width, height)

      verify { fileStore.write(existingRow.storageUrl!!, any()) }

      assertEquals(
          listOf(
              ThumbnailsRow(
                  fileId = fileId,
                  width = width,
                  height = height,
                  contentType = MediaType.IMAGE_JPEG_VALUE,
                  createdTime = Instant.EPOCH,
                  size = actual.size.toInt(),
                  storageUrl = existingRow.storageUrl!!,
              )
          ),
          thumbnailsDao.findAll().map { it.copy(id = null) },
      )
    }
  }

  @Nested
  inner class GetExistingThumbnailData {
    @Test
    fun `returns existing thumbnail of requested size`() {
      val expected = Random.nextBytes(10)

      insertThumbnail(80, 70, expected)

      listOf(80 to 70, 80 to null, null to 70).forEach { (width, height) ->
        val actual = store.getExistingThumbnailData(fileId, width, height)

        assertArrayEquals(expected, actual?.readAllBytes(), "Width $width, height $height")
      }
    }

    @Test
    fun `returns null if no existing thumbnail matches requested size`() {
      insertThumbnail(30, 20, Random.nextBytes(10))

      assertNull(store.getExistingThumbnailData(fileId, 33, 22))
    }
  }

  @Nested
  inner class GenerateThumbnailFromExistingThumbnail {
    @Test
    fun `returns null if there is no existing thumbnail`() {
      assertNull(store.generateThumbnailFromExistingThumbnail(fileId, 50, 50))
    }

    @Test
    fun `stores new thumbnail in thumb subdirectory of original file`() {
      val existingThumbnailUrl = insertThumbnail(1, 1, onePixelPng).storageUrl!!

      every { fileStore.read(existingThumbnailUrl) } answers
          {
            SizedInputStream(ByteArrayInputStream(onePixelPng), onePixelPng.size.toLong())
          }
      justRun { fileStore.write(any(), any()) }

      val thumbnailStream = store.generateThumbnailFromExistingThumbnail(fileId, 50, null)
      assertNotNull(thumbnailStream)
      thumbnailStream.close()

      val newThumbnailUrl =
          thumbnailsDao.findAll().map { it.storageUrl!! }.single { it != existingThumbnailUrl }

      assertEquals("$existingThumbnailUrl".replace("1x1", "50x50"), "$newThumbnailUrl")
    }
  }

  @Nested
  inner class DeleteThumbnails {
    @Test
    fun `deletes multiple thumbnails`() {
      val rows = listOf(insertThumbnail(10, 10), insertThumbnail(20, 20))

      store.deleteThumbnails(fileId)

      verify { fileStore.delete(rows[0].storageUrl!!) }
      verify { fileStore.delete(rows[1].storageUrl!!) }

      assertTableEmpty(THUMBNAILS)
    }

    @Test
    fun `leaves database row in place if deletion from file store fails`() {
      val rows = listOf(insertThumbnail(10, 10), insertThumbnail(20, 20))

      every { fileStore.delete(rows[1].storageUrl!!) } throws IOException("Nope")

      assertThrows<IOException> { store.deleteThumbnails(fileId) }

      verify { fileStore.delete(rows[0].storageUrl!!) }
      verify { fileStore.delete(rows[1].storageUrl!!) }

      assertEquals(listOf(rows[1]), thumbnailsDao.findAll())
    }

    @Test
    fun `does not treat nonexistent thumbnail file as an error`() {
      val row = insertThumbnail(10, 10)

      every { fileStore.delete(row.storageUrl!!) } throws NoSuchFileException("Missing")

      store.deleteThumbnails(fileId)

      verify { fileStore.delete(row.storageUrl!!) }

      assertTableEmpty(THUMBNAILS)
    }
  }

  @Test
  fun `service uses image utils to read photo urls`() {
    val width = photoWidth / 10
    val height = photoHeight / 10

    justRun { fileStore.write(any(), any()) }

    store.getThumbnailData(fileId, width, height)

    verify(exactly = 1) { imageUtils.read(photoStorageUrl) }
  }

  companion object {
    private const val photoWidth = 640
    private const val photoHeight = 480

    private val photoJpegData: ByteArray by lazy {
      val canvas = BufferedImage(photoWidth, photoHeight, BufferedImage.TYPE_INT_RGB)
      val outputStream = ByteArrayOutputStream()
      ImageIO.write(canvas, "JPEG", outputStream)
      outputStream.toByteArray()
    }

    private val photoPngData: ByteArray by lazy {
      val canvas = BufferedImage(photoWidth, photoHeight, BufferedImage.TYPE_INT_ARGB)
      val outputStream = ByteArrayOutputStream()
      ImageIO.write(canvas, "PNG", outputStream)
      outputStream.toByteArray()
    }
  }
}
