package com.terraformation.backend.util

import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.UnsupportedMediaTypeException

internal class ImageUtilsTest {
  private val fileStore: FileStore = mockk()
  private val utils: ImageUtils = spyk(ImageUtils(fileStore))
  private val photoStorageUrl = URI("file:///a/b/c/original.jpg")

  @BeforeEach
  fun setUp() {
    every { fileStore.read(photoStorageUrl) } answers
        {
          SizedInputStream(
              ByteArrayInputStream(ImageUtilsTest.photoData),
              ImageUtilsTest.photoData.size.toLong(),
          )
        }
  }

  @Test
  fun `should not process image for orientation 1`() {
    every { utils.getOrientation(any()) } returns 1
    val image = utils.read(photoStorageUrl)

    assertNotNull(image)

    verify(exactly = 1) { utils.getOrientation(photoStorageUrl) }
    verify(exactly = 0) { utils.flipHorizontal(any()) }
    verify(exactly = 0) { utils.flipVertical(any()) }
    verify(exactly = 0) { utils.rotateByDegree(any(), any()) }
  }

  @Test
  fun `should flip image horizontally for orientation 2`() {
    every { utils.getOrientation(any()) } returns 2
    val image = utils.read(photoStorageUrl)

    assertNotNull(image)

    verify(exactly = 1) { utils.getOrientation(photoStorageUrl) }
    verify(exactly = 1) { utils.flipHorizontal(any()) }
    verify(exactly = 0) { utils.flipVertical(any()) }
    verify(exactly = 0) { utils.rotateByDegree(any(), any()) }
  }

  @Test
  fun `should rotate image by 180 degrees for orientation 3`() {
    every { utils.getOrientation(any()) } returns 3
    val image = utils.read(photoStorageUrl)

    assertNotNull(image)

    verify(exactly = 1) { utils.getOrientation(photoStorageUrl) }
    verify(exactly = 0) { utils.flipHorizontal(any()) }
    verify(exactly = 0) { utils.flipVertical(any()) }
    verify(exactly = 1) { utils.rotateByDegree(any(), 180.0) }
  }

  @Test
  fun `should flip image vertically for orientation 4`() {
    every { utils.getOrientation(any()) } returns 4
    val image = utils.read(photoStorageUrl)

    assertNotNull(image)

    verify(exactly = 1) { utils.getOrientation(photoStorageUrl) }
    verify(exactly = 0) { utils.flipHorizontal(any()) }
    verify(exactly = 1) { utils.flipVertical(any()) }
    verify(exactly = 0) { utils.rotateByDegree(any(), any()) }
  }

  @Test
  fun `should flip image horizontally and then rotate image by 270 degrees for orientation 5`() {
    every { utils.getOrientation(any()) } returns 5
    val image = utils.read(photoStorageUrl)

    assertNotNull(image)

    verify(exactly = 1) { utils.getOrientation(photoStorageUrl) }
    verify(exactly = 1) { utils.flipHorizontal(any()) }
    verify(exactly = 0) { utils.flipVertical(any()) }
    verify(exactly = 1) { utils.rotateByDegree(any(), 270.0) }
    verifyOrder {
      utils.flipHorizontal(any())
      utils.rotateByDegree(any(), 270.0)
    }
  }

  @Test
  fun `should rotate image by 90 degrees for orientation 6`() {
    every { utils.getOrientation(any()) } returns 6
    val image = utils.read(photoStorageUrl)

    assertNotNull(image)

    verify(exactly = 1) { utils.getOrientation(photoStorageUrl) }
    verify(exactly = 0) { utils.flipHorizontal(any()) }
    verify(exactly = 0) { utils.flipVertical(any()) }
    verify(exactly = 1) { utils.rotateByDegree(any(), 90.0) }
  }

  @Test
  fun `should flip image horizontally and then rotate image by 90 degrees for orientation 7`() {
    every { utils.getOrientation(any()) } returns 7
    val image = utils.read(photoStorageUrl)

    assertNotNull(image)

    verify(exactly = 1) { utils.getOrientation(photoStorageUrl) }
    verify(exactly = 1) { utils.flipHorizontal(any()) }
    verify(exactly = 0) { utils.flipVertical(any()) }
    verify(exactly = 1) { utils.rotateByDegree(any(), 90.0) }
    verifyOrder {
      utils.flipHorizontal(any())
      utils.rotateByDegree(any(), 90.0)
    }
  }

  @Test
  fun `should rotate image by 270 degrees for orientation 8`() {
    every { utils.getOrientation(any()) } returns 8
    val image = utils.read(photoStorageUrl)

    assertNotNull(image)

    verify(exactly = 1) { utils.getOrientation(photoStorageUrl) }
    verify(exactly = 0) { utils.flipHorizontal(any()) }
    verify(exactly = 0) { utils.flipVertical(any()) }
    verify(exactly = 1) { utils.rotateByDegree(any(), 270.0) }
  }

  @Test
  fun `read of unsupported file type should mention detected file type in exception message`() {
    val html = """<html><head></head><body></body></html>""".toByteArray()

    every { fileStore.read(photoStorageUrl) } answers
        {
          SizedInputStream(ByteArrayInputStream(html), html.size.toLong(), MediaType.IMAGE_JPEG)
        }

    try {
      utils.read(photoStorageUrl)
      fail<String>("Should have thrown exception")
    } catch (e: UnsupportedMediaTypeException) {
      // We just care that the message says the detected content type, not about its exact wording
      if (e.message?.contains("text/html") != true) {
        assertEquals("Expected exception message to include \"text/html\"", e.message)
      }
    }
  }

  companion object {
    private const val photoWidth = 5
    private const val photoHeight = 5

    private val photoData: ByteArray by lazy {
      val canvas = BufferedImage(photoWidth, photoHeight, BufferedImage.TYPE_INT_RGB)
      val outputStream = ByteArrayOutputStream()
      ImageIO.write(canvas, "JPEG", outputStream)
      outputStream.toByteArray()
    }
  }
}
