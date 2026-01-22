package com.terraformation.backend.splat.sqs

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.splat.SplatService
import io.awspring.cloud.sqs.annotation.SqsListener
import jakarta.inject.Named
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

@ConditionalOnProperty("terraware.splatter.enabled")
@Named
class SplatsSqsListener(private val splatService: SplatService) {
  private val log = perClassLogger()

  @SqsListener($$"${terraware.splatter.response-queue-url}")
  fun receiveSplatterResponse(payload: SplatterResponsePayload) {
    log.info("Got response from Splatter service: $payload")

    if (payload.success) {
      splatService.recordSplatSuccess(payload.jobId)
    } else {
      splatService.recordSplatError(
          payload.jobId,
          payload.errorMessage ?: "No error message received",
      )
    }
  }
}

data class SplatterResponseOutputPayload(
    val bucket: String,
    val key: String,
)

data class SplatterResponsePayload(
    val errorMessage: String?,
    val jobId: FileId,
    val output: SplatterResponseOutputPayload?,
    val success: Boolean,
)
