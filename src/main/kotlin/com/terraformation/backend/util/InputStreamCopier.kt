package com.terraformation.backend.util

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Makes InputStream data available to multiple concurrent readers.
 *
 * This class allows the data from an [InputStream] to be consumed by more than one thread without
 * needing to load the entire contents of the stream into memory at once.
 *
 * Typical usage pattern:
 * 1. Instantiate this class with the source input stream.
 * 2. Call [getCopy] as many times as needed, once for each consumer of the stream data.
 * 3. Pass each copy to its own thread to consume.
 * 4. Call [transfer].
 * 5. Call [close] (or put steps 2-4 in a [use] block).
 *
 * Each copy is its own [InputStream] that can be read semi-independently of the other copies.
 * "Semi-independently" means that all the consumers progress through the data at roughly the same
 * pace: if one of them falls too far behind the others, the others will block until the slower
 * reader catches up sufficiently or closes its stream.
 *
 * The motivating use case is to support large file uploads where we need to both store the data on
 * a cloud file storage service and retrieve metadata from video or photo files. Each of those
 * operations requires an [InputStream] it can read independently.
 *
 * You will almost always want to read each copy from its own thread. It's possible to use more than
 * one copy on a single thread, but you'll have to be careful of deadlocks: read too much from one
 * copy and it will block forever, waiting for one of the other copies to catch up.
 */
class InputStreamCopier(
    private val source: InputStream,
    /**
     * The size of the internal buffer in bytes. This determines how much data can be buffered in
     * memory at once, which is how far behind the slowest reader can get before the fastest one
     * blocks to wait for it to catch up. Larger buffers allow more tolerance for speed differences
     * between copies, and allow reading from the source stream in larger chunks, but use more
     * memory. Must be larger than [minReadSize].
     */
    private val bufferSize: Int = 64 * 1024,
    /**
     * The minimum number of bytes to read from the source in each read operation. Reading in larger
     * chunks is more efficient than many small reads. When buffer space is available,
     * [InputStreamCopier] will try to read up to the remaining buffer space, but will wait for at
     * least this much space before attempting any read.
     */
    private val minReadSize: Int = 8192,
) : Closeable {
  private val buffer = ByteArray(bufferSize)
  private val copies = mutableListOf<CopyInputStream>()
  private val sourceExhausted = AtomicBoolean(false)
  private val sourceException = AtomicReference<Exception?>(null)

  /** If true, this */
  @Volatile private var copierClosed = false

  /** If true, the [transfer] function has been called; no more calls to [getCopy] are allowed. */
  @Volatile private var transferStarted = false

  /** The total number of bytes that have been read from the source stream. */
  private val totalBytesRead = AtomicLong(0)

  /**
   * The absolute position in the source stream that corresponds to the first byte in our buffer. As
   * copies consume data and we reclaim buffer space, this position advances forward through the
   * stream, effectively creating a sliding window over the source data. This is a logical stream
   * position, not a buffer array index - use modulo arithmetic to find the actual buffer offset
   * where this position's data is stored in the circular buffer.
   *
   * Example: if bufferStartPosition=5000 and bufferSize=1024, then the first valid byte is at
   * buffer[(5000 % 1024)] = buffer[904].
   */
  private var bufferStartPosition = 0L

  /**
   * The number of bytes currently stored in the circular buffer that contain valid data from the
   * source stream. This starts at 0 and grows as we read from the source, and shrinks as we reclaim
   * buffer space when all copies have consumed earlier data. Note that this is a linear count of
   * valid bytes, not a buffer offset - the actual data may wrap around the end of the buffer array
   * back to the beginning.
   */
  private var validBytes = 0

  private val lock = ReentrantLock()
  private val dataAvailable: Condition = lock.newCondition()

  /**
   * Returns a stream that has a copy of the data from [source]. You can create copies at any time
   * before calling [transfer]. Copies can start reading immediately - they will block until data
   * becomes available when [transfer] is called.
   */
  fun getCopy(): InputStream {
    return lock.withLock {
      if (copierClosed) {
        throw IllegalStateException(
            "Cannot create new copies after InputStreamCopier has been closed"
        )
      }
      if (transferStarted) {
        throw IllegalStateException("Cannot create new copies after transfer() has started")
      }

      CopyInputStream().also { copies.add(it) }
    }
  }

  /**
   * Copies the source stream's data to all the copy streams. Blocks until the entire source stream
   * has been read and all copies have either finished reading the data or have been closed.
   *
   * Does not close the source stream; use [close] for that.
   */
  fun transfer() {
    lock.withLock {
      if (copierClosed) {
        throw IllegalStateException("InputStreamCopier has been closed")
      }
      if (transferStarted) {
        throw IllegalStateException("transfer() has already been called")
      }
      transferStarted = true
    }

    try {
      readSourceIntoBuffer()
    } catch (e: Exception) {
      sourceException.set(e)
      throw e
    } finally {
      sourceExhausted.set(true)
      lock.withLock { dataAvailable.signalAll() }
    }

    // Wait for all copies to finish
    lock.withLock {
      while (anyCopiesActive()) {
        dataAvailable.await()
      }
    }
  }

  /**
   * Closes the source stream. You'll usually want to call this after [transfer] completes, either
   * explicitly or via a try-with-resources or [use] call. Calling this before [transfer] has
   * completed will cause exceptions to be thrown when reading from the copy streams.
   */
  override fun close() {
    lock.withLock {
      if (!copierClosed) {
        copierClosed = true
        dataAvailable.signalAll() // Wake up any waiting copies
        source.close()
      }
    }
  }

  private fun anyCopiesActive() = copies.any { it.isOpen() && !it.isAtEnd() }

  private fun readSourceIntoBuffer() {
    while (!sourceExhausted.get() && anyCopiesActive()) {
      lock.withLock {
        waitForBufferSpace(minReadSize)
        if (sourceExhausted.get() || !anyCopiesActive()) return

        // Calculate where to read in the circular buffer
        val bufferOffset = ((bufferStartPosition + validBytes) % bufferSize).toInt()
        val availableSpace = bufferSize - validBytes

        // Read directly into circular buffer, handling wraparound
        val bytesRead =
            if (bufferOffset + availableSpace <= bufferSize) {
              // Simple case: no wraparound needed
              source.read(buffer, bufferOffset, availableSpace)
            } else {
              // Wraparound case: read in two parts
              val firstPartSize = bufferSize - bufferOffset
              val firstPartRead = source.read(buffer, bufferOffset, firstPartSize)

              if (firstPartRead == -1) {
                -1
              } else if (firstPartRead < firstPartSize) {
                // Source ended before wraparound
                firstPartRead
              } else {
                // Continue reading into the beginning of buffer
                val secondPartSize = availableSpace - firstPartSize
                val secondPartRead = source.read(buffer, 0, secondPartSize)

                if (secondPartRead == -1) {
                  firstPartRead // Return only the first part
                } else {
                  firstPartRead + secondPartRead
                }
              }
            }
        if (bytesRead == -1) {
          return
        }

        validBytes += bytesRead
        totalBytesRead.addAndGet(bytesRead.toLong())
        dataAvailable.signalAll()
      }
    }
  }

  private fun waitForBufferSpace(bytesNeeded: Int) {
    while (anyCopiesActive() && validBytes + bytesNeeded > bufferSize) {
      reclaimBufferSpace()
      if (validBytes + bytesNeeded > bufferSize) {
        dataAvailable.await()
      }
    }
  }

  private fun reclaimBufferSpace() {
    if (copies.isEmpty()) return

    val minPosition =
        copies.filter { copy -> copy.isOpen() }.minOfOrNull { copy -> copy.position.get() }
            ?: return

    // How much can we advance the buffer start?
    val bytesToReclaim = minPosition - bufferStartPosition
    if (bytesToReclaim <= 0) return

    // Advance buffer start and reduce used space
    bufferStartPosition += bytesToReclaim
    validBytes -= bytesToReclaim.toInt()
    dataAvailable.signalAll()
  }

  private fun waitForData(position: Long): Boolean =
      lock.withLock {
        while (
            totalBytesRead.get() <= position &&
                !sourceExhausted.get() &&
                sourceException.get() == null
        ) {
          dataAvailable.await()
        }

        sourceException.get()?.let { throw it }
        return totalBytesRead.get() > position || sourceExhausted.get()
      }

  private fun checkEndOfStream(position: Long): Boolean {
    return sourceExhausted.get() && position >= totalBytesRead.get()
  }

  private fun copyBytesToArray(dest: ByteArray, destOffset: Int, startPosition: Long, length: Int) {
    val relativePosition = startPosition - bufferStartPosition

    if (relativePosition < 0) {
      throw IllegalStateException(
          "Position $startPosition is before buffer start $bufferStartPosition"
      )
    }
    if (relativePosition >= validBytes) {
      val bufferEnd = bufferStartPosition + validBytes
      throw IllegalStateException(
          "Position $startPosition is beyond available data (buffer end: $bufferEnd)"
      )
    }

    if (relativePosition + length > validBytes) {
      throw IllegalStateException(
          "Read request at position $startPosition with length $length would exceed valid data boundary. " +
              "relativePosition=$relativePosition, validBytes=$validBytes, bufferStartPosition=$bufferStartPosition"
      )
    }

    val bufferOffset = ((bufferStartPosition + relativePosition) % bufferSize).toInt()
    val bytesToEnd = bufferSize - bufferOffset

    if (length <= bytesToEnd) {
      // Simple case: no wraparound needed
      System.arraycopy(buffer, bufferOffset, dest, destOffset, length)
    } else {
      // Wraparound case: copy in two parts
      System.arraycopy(buffer, bufferOffset, dest, destOffset, bytesToEnd)
      val remainingBytes = length - bytesToEnd
      System.arraycopy(buffer, 0, dest, destOffset + bytesToEnd, remainingBytes)
    }
  }

  private inner class CopyInputStream : InputStream() {
    /** The position in the source stream this copy has read up to. */
    val position = AtomicLong(0)

    @Volatile private var closed = false

    fun isOpen(): Boolean = !closed

    fun isAtEnd(): Boolean = checkEndOfStream(position.get())

    override fun read(): Int {
      val buffer = ByteArray(1)
      val bytesRead = read(buffer, 0, 1)
      return if (bytesRead == -1) -1 else buffer[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (closed) throw IOException("Stream closed")
      if (copierClosed)
          throw IOException("InputStreamCopier was closed before transfer() completed")
      if (b.isEmpty() || len == 0) return 0
      if (off < 0) {
        throw IndexOutOfBoundsException("Offset cannot be negative: $off")
      }
      if (len < 0) {
        throw IndexOutOfBoundsException("Length cannot be negative: $len")
      }
      if (off + len > b.size) {
        throw IndexOutOfBoundsException(
            "Offset + length ($off + $len = ${off + len}) exceeds buffer size (${b.size})"
        )
      }

      val currentPosition = position.get()
      if (!waitForData(currentPosition)) return -1
      if (checkEndOfStream(currentPosition)) return -1

      val available = (totalBytesRead.get() - currentPosition).toInt()
      val bytesToRead = minOf(len, available)

      lock.withLock { copyBytesToArray(b, off, currentPosition, bytesToRead) }

      position.addAndGet(bytesToRead.toLong())
      lock.withLock { dataAvailable.signalAll() }

      return bytesToRead
    }

    override fun available(): Int {
      if (closed) return 0
      val currentPosition = position.get()
      return (totalBytesRead.get() - currentPosition).coerceAtLeast(0).toInt()
    }

    override fun skip(n: Long): Long {
      if (closed) throw IOException("Stream closed")
      if (copierClosed)
          throw IOException("InputStreamCopier was closed before transfer() completed")
      if (n <= 0) return 0

      val currentPosition = position.get()
      if (!waitForData(currentPosition)) return 0
      if (checkEndOfStream(currentPosition)) return 0

      val available = (totalBytesRead.get() - currentPosition)
      val bytesToSkip = minOf(n, available)

      position.addAndGet(bytesToSkip)
      lock.withLock { dataAvailable.signalAll() }

      return bytesToSkip
    }

    override fun close() {
      closed = true
      lock.withLock { dataAvailable.signalAll() }
    }
  }
}
