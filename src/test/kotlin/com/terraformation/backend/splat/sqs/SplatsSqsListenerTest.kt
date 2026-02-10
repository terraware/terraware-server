package com.terraformation.backend.splat.sqs

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.splat.SplatService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SplatsSqsListenerTest {
  private val splatService: SplatService = mockk()
  private val listener = SplatsSqsListener(splatService)

  private val fileId = FileId(123)

  @BeforeEach
  fun setUp() {
    every { splatService.recordSplatSuccess(any()) } returns Unit
    every { splatService.recordSplatError(any(), any()) } returns Unit
    every { splatService.recordBirdnetSuccess(any()) } returns Unit
    every { splatService.recordBirdnetError(any(), any()) } returns Unit
  }

  @Nested
  inner class ReceiveSplatterResponse {
    @Test
    fun `records splat success when success is true`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId) }
      verify(exactly = 0) { splatService.recordSplatError(any(), any()) }
    }

    @Test
    fun `records splat error when success is false`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = "Test error",
              jobId = fileId,
              output = null,
              success = false,
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatError(fileId, "Test error") }
      verify(exactly = 0) { splatService.recordSplatSuccess(any()) }
    }

    @Test
    fun `uses default error message when none provided`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = null,
              success = false,
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatError(fileId, "No error message received") }
    }
  }

  @Nested
  inner class ReceiveBirdnetResponse {
    @Test
    fun `records BirdNet success when birdnetSuccess is true`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              birdnetSuccess = true,
              birdnetOutput = SplatterResponseOutputPayload("bucket", "birdnet_key"),
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId) }
      verify { splatService.recordBirdnetSuccess(fileId) }
      verify(exactly = 0) { splatService.recordBirdnetError(any(), any()) }
    }

    @Test
    fun `records BirdNet error when birdnetSuccess is false`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              birdnetSuccess = false,
              birdnetErrorMessage = "BirdNet test error",
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId) }
      verify { splatService.recordBirdnetError(fileId, "BirdNet test error") }
      verify(exactly = 0) { splatService.recordBirdnetSuccess(any()) }
    }

    @Test
    fun `uses default error message for BirdNet when none provided`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              birdnetSuccess = false,
              birdnetErrorMessage = null,
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordBirdnetError(fileId, "No error message received") }
    }

    @Test
    fun `does not process BirdNet when birdnetSuccess is null`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              birdnetSuccess = null,
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId) }
      verify(exactly = 0) { splatService.recordBirdnetSuccess(any()) }
      verify(exactly = 0) { splatService.recordBirdnetError(any(), any()) }
    }

    @Test
    fun `handles both splat and BirdNet failures independently`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = "Splat error",
              jobId = fileId,
              output = null,
              success = false,
              birdnetSuccess = false,
              birdnetErrorMessage = "BirdNet error",
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatError(fileId, "Splat error") }
      verify { splatService.recordBirdnetError(fileId, "BirdNet error") }
    }
  }
}
