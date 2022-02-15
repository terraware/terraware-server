package com.terraformation.backend.file

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.PhotoId
import com.terraformation.backend.db.ThumbnailId
import com.terraformation.backend.db.tables.daos.PhotosDao
import com.terraformation.backend.db.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.tables.pojos.PhotosRow
import com.terraformation.backend.db.tables.pojos.ThumbnailsRow
import com.terraformation.backend.mockUser
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType

internal class ThumbnailStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)!!
  private val fileStore: FileStore = mockk()

  private lateinit var photosDao: PhotosDao
  private lateinit var store: ThumbnailStore
  private lateinit var thumbnailsDao: ThumbnailsDao

  private val photoId = PhotoId(1000)
  private val photoStorageUrl = URI("file:///a/b/c/original.jpg")

  override val sequencesToReset: List<String>
    get() = listOf("thumbnail_id_seq")

  @BeforeEach
  fun setUp() {
    val jooqConfig = dslContext.configuration()
    photosDao = PhotosDao(jooqConfig)
    thumbnailsDao = ThumbnailsDao(jooqConfig)

    store = ThumbnailStore(clock, dslContext, fileStore, photosDao, thumbnailsDao)

    insertUser()
    photosDao.insert(
        PhotosRow(
            id = photoId,
            capturedTime = clock.instant(),
            contentType = MediaType.IMAGE_JPEG_VALUE,
            createdBy = user.userId,
            createdTime = clock.instant(),
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            size = 1,
            fileName = "test.jpg",
            storageUrl = photoStorageUrl))

    every { fileStore.getUrl(any()) } answers { URI("file://${firstArg<Path>()}") }
    every { fileStore.read(any()) } throws NoSuchFileException("Not found")
    every { fileStore.read(photoStorageUrl) } answers
        {
          SizedInputStream(ByteArrayInputStream(photoJpegData), photoJpegData.size.toLong())
        }
  }

  private fun insertThumbnail(
      width: Int,
      height: Int,
      imageData: ByteArray = Random.nextBytes(10),
      storageUrl: URI = URI("file:///a/b/c/thumb/original-${width}x$height.jpg")
  ): ThumbnailsRow {
    val thumbnailsRow =
        ThumbnailsRow(
            photoId = photoId,
            width = width,
            height = height,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            createdTime = clock.instant(),
            size = imageData.size,
            storageUrl = storageUrl)
    thumbnailsDao.insert(thumbnailsRow)

    every { fileStore.read(storageUrl) } returns
        SizedInputStream(ByteArrayInputStream(imageData), imageData.size.toLong())

    return thumbnailsRow
  }

  @Test
  fun `returns existing thumbnail of requested size`() {
    val expected = Random.nextBytes(10)

    insertThumbnail(80, 70, expected)

    val actual = store.getThumbnailData(photoId, 80, 70)

    assertArrayEquals(expected, actual.readAllBytes())
  }

  @Test
  fun `returns existing thumbnail if only width is specified`() {
    val expected = Random.nextBytes(10)

    insertThumbnail(80, 70, expected)

    val actual = store.getThumbnailData(photoId, 80, null)

    assertArrayEquals(expected, actual.readAllBytes())
  }

  @Test
  fun `returns existing thumbnail if only height is specified`() {
    val expected = Random.nextBytes(10)

    insertThumbnail(80, 70, expected)

    val actual = store.getThumbnailData(photoId, null, 70)

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
        store.getThumbnailData(photoId, width, height)
      }
    }
  }

  @Test
  fun `returns shorter thumbnail that matches requested width`() {
    val expected = Random.nextBytes(10)
    val width = 80
    val height = 70

    insertThumbnail(width, height - 1, expected)

    val actual = store.getThumbnailData(photoId, width, height)

    assertArrayEquals(expected, actual.readAllBytes())
  }

  @Test
  fun `returns narrower thumbnail that matches requested height`() {
    val expected = Random.nextBytes(10)
    val width = 80
    val height = 70

    insertThumbnail(width - 1, height, expected)

    val actual = store.getThumbnailData(photoId, width, height)

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
    justRun { fileStore.write(capture(thumbUrlSlot), capture(streamSlot), any()) }

    val actual = store.getThumbnailData(photoId, width, height)

    verify(exactly = 1) { fileStore.write(any(), any(), any()) }

    assertEquals(
        URI("file:///a/b/c/thumb/original-${width}x$height.jpg"),
        thumbUrlSlot.captured,
        "Should have derived thumbnail URL from original photo URL")
    assertArrayEquals(
        actual.readAllBytes(),
        streamSlot.captured.readAllBytes(),
        "Should have written same image to file store that was returned to caller")
  }

  @Test
  fun `generates thumbnails as valid JPEG files`() {
    val width = photoWidth / 10
    val height = photoHeight / 10

    justRun { fileStore.write(any(), any(), any()) }

    val actual = store.getThumbnailData(photoId, width, height)
    val actualData = actual.readAllBytes()

    val imageReader = ImageIO.getImageReadersByFormatName("JPEG").next()
    imageReader.input = MemoryCacheImageInputStream(ByteArrayInputStream(actualData))
    val thumbnailImage = imageReader.read(0)

    assertEquals(width, thumbnailImage.width, "Thumbnail width")
    assertEquals(height, thumbnailImage.height, "Thumbnail height")
  }

  @Test
  fun `generates thumbnails above minimum high-quality dimensions`() {
    justRun { fileStore.write(any(), any(), any()) }

    val actual =
        store.getThumbnailData(photoId, store.minSizeForHighQuality, store.minSizeForHighQuality)
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

    every { fileStore.write(any(), any(), any()) } throws FileAlreadyExistsException("Exists")

    val actual = store.getThumbnailData(photoId, width, height)

    assertEquals(
        listOf(
            ThumbnailsRow(
                id = ThumbnailId(1),
                photoId = photoId,
                width = width,
                height = height,
                contentType = MediaType.IMAGE_JPEG_VALUE,
                createdTime = Instant.EPOCH,
                size = actual.size.toInt(),
                storageUrl = URI("file:///a/b/c/thumb/original-${width}x$height.jpg"))),
        thumbnailsDao.findAll())
  }

  @Test
  fun `regenerates thumbnail if file is missing`() {
    val width = photoWidth / 10
    val height = photoHeight / 10

    val existingRow = insertThumbnail(width, height)

    every { fileStore.read(existingRow.storageUrl!!) } throws NoSuchFileException("Nope")
    justRun { fileStore.write(any(), any(), any()) }

    val actual = store.getThumbnailData(photoId, width, height)

    verify { fileStore.write(existingRow.storageUrl!!, any(), any()) }

    assertEquals(
        listOf(
            ThumbnailsRow(
                photoId = photoId,
                width = width,
                height = height,
                contentType = MediaType.IMAGE_JPEG_VALUE,
                createdTime = Instant.EPOCH,
                size = actual.size.toInt(),
                storageUrl = existingRow.storageUrl!!)),
        thumbnailsDao.findAll().map { it.copy(id = null) })
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
  }
}
