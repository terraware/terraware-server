package com.terraformation.backend.file

import java.time.Instant
import kotlin.io.path.extension
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PathGeneratorTest {
  private val timestamp = Instant.parse("2024-03-15T12:34:56Z")
  private val pathGenerator = PathGenerator(Random(0))

  @Test
  fun `uses extension of content type`() {
    val path = pathGenerator.generatePath(timestamp, "category", "image/jpeg")

    assertEquals("jpg", path.extension)
  }

  @Test
  fun `uses extension of content type if it differs from filename extension`() {
    val path = pathGenerator.generatePath(timestamp, "category", "image/jpeg", "photo.png")

    assertEquals("jpg", path.extension)
  }

  @Test
  fun `uses filename extension if content type has no extension`() {
    val path =
        pathGenerator.generatePath(timestamp, "category", "application/json+frames", "frames.json")

    assertEquals("json", path.extension)
  }

  @Test
  fun `converts filename extension to lowercase`() {
    val path = pathGenerator.generatePath(timestamp, "category", "application/x-bogus", "DATA.JSON")

    assertEquals("json", path.extension)
  }

  @Test
  fun `uses default extension if content type has no extension and there is no filename`() {
    val path = pathGenerator.generatePath(timestamp, "category", "application/json+frames")

    assertEquals("bin", path.extension)
  }

  @Test
  fun `uses default extension if filename has no extension`() {
    val path = pathGenerator.generatePath(timestamp, "category", "application/x-bogus", "frames")

    assertEquals("bin", path.extension)
  }

  @Test
  fun `uses default extension if filename extension is not alphanumeric`() {
    val path =
        pathGenerator.generatePath(timestamp, "category", "application/x-bogus", "frames.j/../son")

    assertEquals("bin", path.extension)
  }
}
