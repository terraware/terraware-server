package com.terraformation.backend.file.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.log.perClassLogger
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

@InternalEndpoint
@RequestMapping("/api/v1/webhooks/mux")
@RestController
class MuxWebhookController(
    private val config: TerrawareServerConfig,
    private val objectMapper: ObjectMapper,
) {
  private val log = perClassLogger()

  @PostMapping
  @Operation(summary = "Endpoint for webhook requests from Mux.")
  fun muxWebhook(
      @RequestHeader("Mux-Signature") muxSignature: String?,
      @RequestBody payload: ByteArray,
  ): String {
    verifyMuxSignature(payload, muxSignature)

    val payloadJson = objectMapper.readTree(payload)

    // TODO: Use the payload to update the status of the appropriate file in our DB
    log.debug("Webhook payload: $payloadJson")

    return "ok"
  }

  private fun verifyMuxSignature(payload: ByteArray, muxSignature: String?) {
    if (muxSignature == null) {
      throw BadRequestException("No signature found")
    }

    val webhookSecret =
        config.mux.webhookSecret?.toByteArray()
            ?: throw InternalServerErrorException("No webhook secret configured")

    val signatureParts =
        muxSignature
            .split(',')
            .map { it.split('=', limit = 2) }
            .onEach { if (it.size != 2) throw BadRequestException("Signature parts invalid") }
            .associate { it[0] to it[1] }
    val timestamp =
        signatureParts["t"] ?: throw BadRequestException("No timestamp found in signature")
    val expectedHash =
        signatureParts["v1"] ?: throw BadRequestException("No hash found in signature")

    val hmac = HmacUtils.getInitializedMac(HmacAlgorithms.HMAC_SHA_256, webhookSecret)
    hmac.update(timestamp.toByteArray())
    hmac.update(PERIOD_BYTE)
    val actualHashBytes = hmac.doFinal(payload)
    val actualHashHex = HexFormat.of().formatHex(actualHashBytes)

    if (expectedHash != actualHashHex) {
      log.debug("Hash mismatch: expected $expectedHash, was $actualHashHex")
      throw BadRequestException("Signature does not match request")
    }
  }

  companion object {
    const val PERIOD_BYTE = '.'.code.toByte()
  }
}
