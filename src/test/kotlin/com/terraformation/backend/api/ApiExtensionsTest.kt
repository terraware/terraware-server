package com.terraformation.backend.api

import jakarta.ws.rs.NotSupportedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile

class ApiExtensionsTest {
  @Nested
  inner class MultipartFileGetPlainContentType {
    @Test
    fun `returns content type without extended information`() {
      val file = mockMultipartFile(contentType = "text/plain;charset=UTF-8")
      assertEquals("text/plain", file.getPlainContentType())
    }

    @Test
    fun `returns null if content type is null`() {
      val file = mockMultipartFile(contentType = null)
      assertEquals(null, file.getPlainContentType())
    }

    @Test
    fun `lowercases content type`() {
      val file = mockMultipartFile(contentType = "TEXT/PLAIN")
      assertEquals("text/plain", file.getPlainContentType())
    }

    @Test
    fun `returns content type if it matches allowed types`() {
      val file = mockMultipartFile(contentType = "text/plain;charset=UTF-8")
      val allowedTypes = setOf(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON)

      assertEquals("text/plain", file.getPlainContentType(allowedTypes))
    }

    @Test
    fun `returns content type if wildcard matches allowed types`() {
      val file = mockMultipartFile(contentType = "image/jpeg")
      val allowedTypes = setOf(MediaType.parseMediaType("image/*"))

      assertEquals("image/jpeg", file.getPlainContentType(allowedTypes))
    }

    @Test
    fun `throws NotSupportedException if content type not in allowed types`() {
      val file = mockMultipartFile(contentType = "application/octet-stream")
      val allowedTypes = setOf(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON)

      assertThrows(NotSupportedException::class.java) { file.getPlainContentType(allowedTypes) }
    }

    @Test
    fun `throws NotSupportedException if content type is null`() {
      val file = mockMultipartFile(contentType = null)
      val allowedTypes = setOf(MediaType.TEXT_PLAIN)

      assertThrows<NotSupportedException> { file.getPlainContentType(allowedTypes) }
    }

    @Test
    fun `throws NotSupportedException if content type is invalid`() {
      val file = mockMultipartFile(contentType = "invalid-content-type")
      val allowedTypes = setOf(MediaType.TEXT_PLAIN)

      assertThrows<NotSupportedException> { file.getPlainContentType(allowedTypes) }
    }
  }

  @Nested
  inner class MultipartFileGetFilename {
    @Test
    fun `returns original filename when present`() {
      val file = mockMultipartFile(originalFilename = "document.pdf")
      assertEquals("document.pdf", file.getFilename())
    }

    @Test
    fun `constructs filename when original filename is missing`() {
      val file = mockMultipartFile(originalFilename = null, contentType = "application/json")
      assertEquals("data.json", file.getFilename("data"))
    }

    @Test
    fun `constructs filename with no extension when content type is unknown`() {
      val file = mockMultipartFile(originalFilename = null, contentType = "application/x-unknown")
      assertEquals("upload", file.getFilename())
    }

    @Test
    fun `constructs filename with octet-stream extension when content type is null`() {
      val file = mockMultipartFile(originalFilename = null, contentType = null)
      assertEquals("upload.bin", file.getFilename())
    }
  }

  fun mockMultipartFile(
      name: String = "file",
      originalFilename: String? = "test.bin",
      contentType: String? = "image/jpeg",
  ) = MockMultipartFile(name, originalFilename, contentType, "content".toByteArray())
}
