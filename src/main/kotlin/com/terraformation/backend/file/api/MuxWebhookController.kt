package com.terraformation.backend.file.api

import MuxWebhookRequestPayload
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.log.withMDC
import io.swagger.v3.oas.annotations.Operation
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.InternalServerErrorException
import java.util.HexFormat
import org.apache.commons.codec.digest.HmacAlgorithms
import org.apache.commons.codec.digest.HmacUtils
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Handles webhook requests from Mux. We only pay attention to requests that tell us the status of
 * newly-created assets; other types of webhook requests are acknowledged but ignored.
 *
 * Docs: https://www.mux.com/docs/webhook-reference
 */
@InternalEndpoint
@RequestMapping("/api/v1/webhooks/mux")
@RestController
class MuxWebhookController(
    private val config: TerrawareServerConfig,
    private val objectMapper: ObjectMapper,
    private val muxService: MuxService,
) {
  private val log = perClassLogger()

  @PostMapping
  @Operation(summary = "Endpoint for webhook requests from Mux.")
  fun handleMuxWebhook(
      @RequestHeader("Mux-Signature") muxSignature: String?,
      @RequestBody rawPayload: ByteArray,
  ): String {
    verifyMuxWebhookSignature(rawPayload, muxSignature)

    val rawPayloadString = rawPayload.decodeToString()

    val payload =
        try {
          objectMapper.readValue<MuxWebhookRequestPayload>(rawPayloadString)
        } catch (e: Exception) {
          // It's probably a bug on our end, so just log and acknowledge the request rather than
          // returning an error. Otherwise Mux would keep retrying it.
          log.withMDC("payload" to rawPayloadString) {
            log.error("Error parsing Mux webhook payload", e)
          }

          return "acknowledged"
        }

    log.withMDC("payload" to rawPayloadString) {
      log.debug("Received Mux webhook request of type ${payload.type}")
    }

    // We store the file ID in the asset's metadata which is sent back to us in webhook requests.
    val fileId = payload.data.fileId
    if (fileId == null) {
      log.error("No file ID found in Mux webhook request")
      return "acknowledged"
    }

    when (payload.type) {
      "video.asset.deleted" -> muxService.markAssetDeleted(fileId)
      "video.asset.errored" -> muxService.markAssetErrored(fileId, payload.data.errorMessage)
      "video.asset.ready" -> muxService.markAssetReady(fileId)
    }

    return "ok"
  }

  /**
   * Throws [BadRequestException] if the Mux-Signature line doesn't match the expected signature
   * based on the request payload and our webhook secret.
   *
   * Mux signatures are documented here: https://www.mux.com/docs/core/verify-webhook-signatures
   */
  private fun verifyMuxWebhookSignature(payload: ByteArray, muxSignature: String?) {
    if (muxSignature == null) {
      throw BadRequestException("No signature found")
    }

    val webhookSecret =
        config.mux.webhookSecret?.toByteArray()
            ?: throw InternalServerErrorException("No webhook secret configured")

    // Signature line looks like: t=X,v1=Y where X is a timestamp and v1 is a hex HMAC-SHA256 hash
    // of the timestamp plus the request body using the webhook secret as the key.
    val signatureParts =
        muxSignature
            .split(',')
            .map { it.split('=', limit = 2) }
            .onEach { if (it.size != 2) throw BadRequestException("Signature parts invalid") }
            .associate { it[0] to it[1] }
    val timestamp =
        signatureParts["t"] ?: throw BadRequestException("No timestamp found in signature")
    val hashFromHeader =
        signatureParts["v1"] ?: throw BadRequestException("No hash found in signature")

    val hmac = HmacUtils.getInitializedMac(HmacAlgorithms.HMAC_SHA_256, webhookSecret)
    hmac.update(timestamp.toByteArray())
    hmac.update(PERIOD_BYTE)
    val payloadHash = HexFormat.of().formatHex(hmac.doFinal(payload))

    if (hashFromHeader != payloadHash) {
      log.debug("Hash mismatch: expected $payloadHash but header had $hashFromHeader")
      throw BadRequestException("Signature does not match request")
    }
  }

  companion object {
    const val PERIOD_BYTE = '.'.code.toByte()
  }
}
