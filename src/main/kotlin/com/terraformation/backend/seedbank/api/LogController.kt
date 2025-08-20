package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.log.log
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/log")
@RestController
@SeedBankAppEndpoint
class LogController(private val objectMapper: ObjectMapper) {
  @Operation(summary = "Records a log message from a device at a seed bank.")
  @PostMapping("/{tag}")
  fun recordLogMessage(
      @PathVariable
      @Schema(description = "Source of the log message.", example = "seedbank-app")
      tag: String,
      @RequestBody
      @Schema(
          description =
              "Values to log. This can be an arbitrary bucket of key/value pairs, but the " +
                  "'level' field should be set to one of 'debug', 'info', 'warn', or " +
                  "'error'. Default level is 'info'. If there is a human-readable message, " +
                  "it should go in the 'message' field."
      )
      payload: Map<String, Any>,
  ) {
    val level =
        when (payload["level"]?.toString()?.lowercase()) {
          "debug" -> Level.DEBUG
          "warn",
          "warning" -> Level.WARN
          "error" -> Level.ERROR
          else -> Level.INFO
        }
    LoggerFactory.getLogger("client.$tag").log(level, objectMapper.writeValueAsString(payload))
  }
}
