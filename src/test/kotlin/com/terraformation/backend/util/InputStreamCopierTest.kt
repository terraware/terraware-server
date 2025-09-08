package com.terraformation.backend.util

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows

class InputStreamCopierTest {

  @Test
  fun `single copy reads entire stream correctly`() {
    val testData = "Hello, World!".toByteArray()
    val source = ByteArrayInputStream(testData)
    val copier = InputStreamCopier(source)

    val copy = copier.getCopy()

    var result: ByteArray? = null
    val readerThread = thread { result = copy.readAllBytes() }

    copier.transfer()
    readerThread.join()

    assertArrayEquals(testData, result)
  }

  @Test
  fun `multiple copies read identical data`() {
    val testData = "This is a test with multiple copies".toByteArray()
    val source = ByteArrayInputStream(testData)
    val copier = InputStreamCopier(source)

    val copy1 = copier.getCopy()
    val copy2 = copier.getCopy()
    val copy3 = copier.getCopy()

    val results = Array<ByteArray?>(3) { null }
    val threads =
        listOf(
            thread { results[0] = copy1.readAllBytes() },
            thread { results[1] = copy2.readAllBytes() },
            thread { results[2] = copy3.readAllBytes() },
        )

    copier.transfer()
    threads.forEach { it.join() }

    assertArrayEquals(testData, results[0])
    assertArrayEquals(testData, results[1])
    assertArrayEquals(testData, results[2])
  }

  @Test
  fun `empty source stream`() {
    val source = ByteArrayInputStream(ByteArray(0))
    val copier = InputStreamCopier(source)

    val copy = copier.getCopy()

    var result: ByteArray? = null
    val readerThread = thread { result = copy.readAllBytes() }

    copier.transfer()
    readerThread.join()

    assertEquals(0, result?.size)
  }

  @Test
  fun `can create copies dynamically before transfer`() {
    val testData = generateTestData(10000)
    val source = ByteArrayInputStream(testData)
    val copier = InputStreamCopier(source)

    val copy1 = copier.getCopy()
    var result1: ByteArray? = null

    val reader1StartedLatch = CountDownLatch(1)
    val reader1Thread = thread {
      reader1StartedLatch.countDown()
      result1 = copy1.readAllBytes()
    }

    reader1StartedLatch.await()
    // Give it a little time to actually start reading; no good way to make this deterministic
    Thread.sleep(5)

    val copy2 = copier.getCopy()
    var result2: ByteArray? = null
    val reader2Thread = thread { result2 = copy2.readAllBytes() }

    copier.transfer()
    reader1Thread.join()
    reader2Thread.join()

    assertArrayEquals(testData, result1)
    assertArrayEquals(testData, result2)
  }

  @Test
  fun `multiple copies read concurrently with different speeds`() {
    val testData = generateTestData(3000)
    val source = ByteArrayInputStream(testData)
    val copier = InputStreamCopier(source, bufferSize = 1000, minReadSize = 50)

    val copy1 = copier.getCopy()
    val copy2 = copier.getCopy()

    var slowResult: ByteArray? = null
    var fastResult: ByteArray? = null
    val exceptions = ConcurrentLinkedQueue<Exception>()

    // Fast reader
    val fastReader = thread {
      try {
        fastResult = copy1.readAllBytes()
      } catch (e: Exception) {
        exceptions.add(e)
      }
    }

    // Slow reader with artificial delays
    val slowReader = thread {
      try {
        val buffer = mutableListOf<Byte>()
        var byte: Int
        while (copy2.read().also { byte = it } != -1) {
          buffer.add(byte.toByte())
          if (buffer.size % 100 == 0) {
            Thread.sleep(1)
          }
        }
        slowResult = buffer.toByteArray()
      } catch (e: Exception) {
        exceptions.add(e)
      }
    }

    copier.transfer()

    fastReader.join()
    slowReader.join()

    assertEquals(emptyList<Exception>(), exceptions.toList(), "Exceptions")

    assertArrayEquals(testData, slowResult, "Result from slow reader")
    assertArrayEquals(testData, fastResult, "Result from fast reader")
  }

  @Test
  fun `source exception propagated to all copies`() {
    val failingStream =
        object : InputStream() {
          override fun read(): Int {
            throw SocketTimeoutException("Test exception")
          }
        }

    val copier = InputStreamCopier(failingStream)
    val copy1 = copier.getCopy()
    val copy2 = copier.getCopy()

    val exceptions = ConcurrentLinkedQueue<Exception>()
    val latch = CountDownLatch(2)

    val threads =
        listOf(
            thread {
              try {
                copy1.readAllBytes()
              } catch (e: Exception) {
                exceptions.add(e)
              } finally {
                latch.countDown()
              }
            },
            thread {
              try {
                copy2.readAllBytes()
              } catch (e: Exception) {
                exceptions.add(e)
              } finally {
                latch.countDown()
              }
            },
        )

    assertThrows<IOException> { copier.transfer() }

    assertTrue(latch.await(5000, TimeUnit.MILLISECONDS), "All readers should complete")
    threads.forEach { it.join() }

    assertEquals(2, exceptions.size, "Both copies should receive the exception")
    exceptions.forEach { exception ->
      assertInstanceOf<IOException>(exception, "Top-level exception")
      assertInstanceOf<SocketTimeoutException>(exception.cause, "Nested exception")
      assertEquals("Test exception", exception.cause?.message)
    }
  }

  @Test
  fun `read methods work correctly`() {
    val testData = "Hello, World! This is a longer test string.".toByteArray()
    val source = ByteArrayInputStream(testData)
    val copier = InputStreamCopier(source)
    val copy = copier.getCopy()

    var firstByte: Int = -1
    var bytesRead: Int = -1
    var bytesRead2: Int = -1
    val buffer = ByteArray(5)
    val buffer2 = ByteArray(10)
    var remaining: ByteArray? = null
    var endByte: Int = -1

    val readerThread = thread {
      // Single byte read
      firstByte = copy.read()

      // Whole array read
      bytesRead = copy.read(buffer)

      // Array read with offset and length
      bytesRead2 = copy.read(buffer2, 2, 6)

      // Read remaining data
      remaining = copy.readAllBytes()

      // Read when stream exhausted
      endByte = copy.read()
    }

    copier.transfer()
    readerThread.join()

    assertEquals(testData[0].toInt() and 0xFF, firstByte, "First byte")
    assertEquals(5, bytesRead, "First array read length")
    assertArrayEquals(testData.sliceArray(1..5), buffer, "First array read")
    assertEquals(6, bytesRead2, "Second array read length")
    assertArrayEquals(testData.sliceArray(6..11), buffer2.sliceArray(2..7), "Second array read")
    assertEquals(-1, endByte, "Remainder read length")
    assertArrayEquals(testData.sliceArray(12 until testData.size), remaining, "Remainder read")
  }

  @Test
  fun `closed copy stream throws exception on read`() {
    val testData = "test".toByteArray()
    val source = ByteArrayInputStream(testData)
    val copier = InputStreamCopier(source)
    val copy = copier.getCopy()

    copy.close()

    assertThrows<IOException> { copy.read() }
    assertThrows<IOException> { copy.read(ByteArray(10)) }
    assertEquals(0, copy.available(), "Bytes available")
  }

  @Test
  fun `skip method skips input`() {
    val testData = generateTestData(1000)
    val source = ByteArrayInputStream(testData)
    val copier = InputStreamCopier(source)
    val copy = copier.getCopy()

    var bytesSkipped = 0L
    var remainingData: ByteArray? = null

    val readerThread = thread {
      // Skip the first 100 bytes
      bytesSkipped = copy.skip(100)

      // Read the remaining data
      remainingData = copy.readAllBytes()
    }

    copier.transfer()
    readerThread.join()

    assertEquals(100, bytesSkipped, "Should skip exactly 100 bytes")
    assertEquals(900, remainingData?.size, "Should have 900 bytes remaining")

    val expectedRemaining = testData.sliceArray(100..<testData.size)
    assertArrayEquals(
        expectedRemaining,
        remainingData,
        "Should have read data after the skipped bytes",
    )
  }

  @Test
  fun `creating copies after transfer throws exception`() {
    val source = ByteArrayInputStream(generateTestData(5))
    val copier = InputStreamCopier(source)

    val copy1 = copier.getCopy()

    val readerThread = thread { copy1.readAllBytes() }

    copier.transfer()

    assertThrows<IllegalStateException> { copier.getCopy() }

    readerThread.join()
  }

  @Test
  fun `transfer waits for all copies to finish or close`() {
    val testData = generateTestData(1000)
    val source = ByteArrayInputStream(testData)
    val copier = InputStreamCopier(source)

    val copy1 = copier.getCopy()
    val copy2 = copier.getCopy()
    val copy3 = copier.getCopy()

    var closingReaderCompleted = false
    var slowReaderCompleted = false

    val fastReader = thread { copy1.readAllBytes() }

    val slowReader = thread {
      Thread.sleep(100) // Small delay to ensure transfer() waits
      copy2.readAllBytes()
      slowReaderCompleted = true
    }

    val closingReader = thread {
      copy3.close()
      closingReaderCompleted = true
    }

    copier.transfer()

    assertTrue(closingReaderCompleted, "Should have waited for closing reader to complete")
    assertTrue(slowReaderCompleted, "Should have waited for slow reader to complete")

    closingReader.join()
    fastReader.join()
    slowReader.join()
  }

  @Test
  fun `close without transfer causes copy reads to throw exception`() {
    val testData = generateTestData(1000)
    val source = ByteArrayInputStream(testData)
    val copier = InputStreamCopier(source)

    val copy1 = copier.getCopy()
    val copy2 = copier.getCopy()

    // Close without calling transfer
    copier.close()

    assertThrows<IOException> { copy1.read() }
    assertThrows<IOException> { copy2.readAllBytes() }
  }

  @Test
  fun `available correctly counts wrapped buffer data`() {
    val testData = generateTestData(2000)
    val source = ByteArrayInputStream(testData)
    val copier = InputStreamCopier(source, bufferSize = 800, minReadSize = 200)

    var availableAfterBufferReplenish: Int? = null

    val fastCopy = copier.getCopy()
    val slowCopy = copier.getCopy()

    val testThread = thread {
      // We'll read from both copies in one thread and close them both afterwards since we need to
      // read from them in a precise sequence.
      fastCopy.use {
        slowCopy.use {
          // Consume the entire circular buffer's worth of data; this will make it available to the
          // slow copy too.
          fastCopy.read(ByteArray(800))

          // Consume most of the buffer's worth of data on the slow copy, meaning the first part of
          // the buffer is available for further reads from the source stream.
          slowCopy.read(ByteArray(500))

          // Consume more data from the fast copy, which will trigger another read from the source
          // stream to fill up the first 500 bytes of the buffer (which are now unused because the
          // slow copy has consumed them already).
          fastCopy.read()

          // Now the slow copy should see the additional available bytes.
          availableAfterBufferReplenish = slowCopy.available()
        }
      }
    }

    copier.transfer()

    testThread.join()

    assertEquals(800, availableAfterBufferReplenish, "Available after buffer replenished")
  }

  @Test
  fun `close completes quickly without waiting`() {
    val testData = generateTestData(1000)
    val source = ByteArrayInputStream(testData)
    val copier = InputStreamCopier(source)

    val copy1 = copier.getCopy()

    val copierClosedLatch = CountDownLatch(1)
    var copyRunning = true

    val copyThread = thread {
      try {
        // Wait for main thread to tell us it's done asserting that it didn't wait for us
        copierClosedLatch.await(15, TimeUnit.SECONDS)
        copy1.close()
      } finally {
        copyRunning = false
      }
    }

    copier.close()

    assertTrue(copyRunning, "Copy thread should still be running after close")

    copierClosedLatch.countDown()
    copyThread.join()
  }

  private fun generateTestData(size: Int): ByteArray {
    return ByteArray(size) { (it % 256).toByte() }
  }
}
