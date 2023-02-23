package com.terraformation.backend.api

import com.terraformation.backend.file.SizedInputStream
import java.io.InputStreamReader
import javax.ws.rs.NotSupportedException
import org.apache.commons.fileupload.FileItemStream
import org.apache.tika.mime.MimeTypes
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.multipart.MultipartFile

/** Reads an item of a streaming file-upload form submission as a string. */
fun FileItemStream.readString(): String {
  return openStream().use { InputStreamReader(it).readText() }
}

/**
 * Wraps a SizedInputStream in a response entity suitable for use as a return value from a
 * controller method.
 */
fun SizedInputStream.toResponseEntity(
    addHeaders: (HttpHeaders.() -> Unit)? = null
): ResponseEntity<InputStreamResource> {
  val headers = HttpHeaders()

  headers.contentLength = size
  headers.contentType = contentType
  addHeaders?.invoke(headers)

  val resource = InputStreamResource(this)
  return ResponseEntity(resource, headers, HttpStatus.OK)
}

/**
 * Returns the content type of an uploaded file, minus any extended information. For example, if the
 * client-supplied content type is, "text/plain;charset=UTF-8", returns "text/plain".
 */
fun MultipartFile.getPlainContentType(): String? {
  return this.contentType?.substringBefore(';')?.lowercase()
}

/**
 * Returns the content type of an uploaded file, minus any extended information. For example, if the
 * client-supplied content type is, "text/plain;charset=UTF-8", returns "text/plain".
 *
 * @param allowedTypes Only accept content types from this set; throw NotSupportedException for
 *   types that aren't listed.
 */
fun MultipartFile.getPlainContentType(allowedTypes: Set<String>): String {
  val contentType = this.getPlainContentType()

  if (contentType == null || contentType !in allowedTypes) {
    throw NotSupportedException("Content type must be one of: ${allowedTypes.sorted()}")
  }

  return contentType
}

/**
 * Returns the filename of an uploaded file. If the client supplied a filename, returns it as-is.
 * Otherwise, constructs a filename based on a default base name and an appropriate extension for
 * the content type, if any.
 */
fun MultipartFile.getFilename(defaultBaseName: String = "upload"): String {
  return originalFilename
      ?: run {
        val contentType = getPlainContentType() ?: MediaType.APPLICATION_OCTET_STREAM_VALUE
        val extension = MimeTypes.getDefaultMimeTypes().getRegisteredMimeType(contentType) ?: ""
        defaultBaseName + extension
      }
}
