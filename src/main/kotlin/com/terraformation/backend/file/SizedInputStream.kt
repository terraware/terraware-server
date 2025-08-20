package com.terraformation.backend.file

import java.io.InputStream
import java.io.OutputStream
import org.springframework.http.MediaType

/**
 * Input stream whose total size, and possibly content type, is known in advance. This delegates all
 * methods to an underlying stream but exposes a property for the total size of the stream.
 *
 * This allows us to avoid extra round trips to external file storage services that already include
 * the file size as part of their "stream a file's contents" return values.
 *
 * [size] can differ from [available] since [available] returns the number of bytes that can be read
 * _without blocking_, and there might be additional content that requires blocking.
 */
class SizedInputStream(
    private val stream: InputStream,
    val size: Long,
    val contentType: MediaType? = null,
) : InputStream() {
  override fun available(): Int = stream.available()

  override fun close() = stream.close()

  override fun mark(readlimit: Int) = stream.mark(readlimit)

  override fun markSupported(): Boolean = stream.markSupported()

  override fun read(): Int = stream.read()

  override fun read(b: ByteArray): Int = stream.read(b)

  override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)

  override fun readAllBytes(): ByteArray = stream.readAllBytes()

  override fun readNBytes(len: Int): ByteArray = stream.readNBytes(len)

  override fun readNBytes(b: ByteArray, off: Int, len: Int): Int = stream.readNBytes(b, off, len)

  override fun reset() = stream.reset()

  override fun skip(n: Long): Long = stream.skip(n)

  override fun skipNBytes(n: Long) = stream.skipNBytes(n)

  override fun transferTo(out: OutputStream): Long = stream.transferTo(out)

  fun withContentType(contentType: String?): SizedInputStream {
    val mediaType = contentType?.let { MediaType.parseMediaType(it) }
    return SizedInputStream(stream, size, mediaType)
  }
}
