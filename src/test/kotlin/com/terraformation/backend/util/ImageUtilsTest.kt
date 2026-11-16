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
import java.util.stream.Stream
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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

  @ParameterizedTest(name = "orientation {0}: {1}")
  @MethodSource("orientations")
  fun `should apply expected transforms`(
      orientation: Int,
      @Suppress("UNUSED_PARAMETER") description: String,
      flipHorizontalCount: Int,
      flipVerticalCount: Int,
      rotationDegrees: Double?,
  ) {
    every { utils.getOrientation(any()) } returns orientation
    val image = utils.read(photoStorageUrl)

    assertNotNull(image)

    verify(exactly = 1) { utils.getOrientation(photoStorageUrl) }
    verify(exactly = flipHorizontalCount) { utils.flipHorizontal(any()) }
    verify(exactly = flipVerticalCount) { utils.flipVertical(any()) }
    if (rotationDegrees != null) {
      verify(exactly = 1) { utils.rotateByDegree(any(), rotationDegrees) }
      if (flipHorizontalCount > 0) {
        verifyOrder {
          utils.flipHorizontal(any())
          utils.rotateByDegree(any(), rotationDegrees)
        }
      }
    } else {
      verify(exactly = 0) { utils.rotateByDegree(any(), any()) }
    }
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
    @JvmStatic
    fun orientations(): Stream<Arguments> =
        Stream.of(
            Arguments.of(1, "no transform", 0, 0, null),
            Arguments.of(2, "flip horizontal", 1, 0, null),
            Arguments.of(3, "rotate 180", 0, 0, 180.0),
            Arguments.of(4, "flip vertical", 0, 1, null),
            Arguments.of(5, "flip horizontal then rotate 270", 1, 0, 270.0),
            Arguments.of(6, "rotate 90", 0, 0, 90.0),
            Arguments.of(7, "flip horizontal then rotate 90", 1, 0, 90.0),
            Arguments.of(8, "rotate 270", 0, 0, 270.0),
        )

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
