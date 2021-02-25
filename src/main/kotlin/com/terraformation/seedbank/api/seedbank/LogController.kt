package com.terraformation.seedbank.api.seedbank

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraformation.seedbank.services.log
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/log")
@RestController
class LogController(private val objectMapper: ObjectMapper) {
  @PostMapping("/{tag}")
  fun recordLogMessage(@PathVariable tag: String, @RequestBody payload: Map<String, Any>) {
    val level =
        when (payload["level"]?.toString()?.toLowerCase()) {
          "debug" -> Level.DEBUG
          "warn", "warning" -> Level.WARN
          "error" -> Level.ERROR
          else -> Level.INFO
        }
    LoggerFactory.getLogger("client.$tag").log(level, objectMapper.writeValueAsString(payload))
  }
}
