package com.terraformation.backend.time.api

import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import java.time.Clock
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@SeedBankAppEndpoint
class ClockController(private val clock: Clock) {
  @GetMapping("/api/v1/seedbank/clock")
  @Operation(
      summary = "Get the server's current date and time.",
      description =
          "In test environments, the clock can be advanced artificially, which will cause it to " +
              "differ from the real-world date and time.",
  )
  fun getCurrentTime(request: HttpServletRequest): GetCurrentTimeResponsePayload {
    return GetCurrentTimeResponsePayload(clock.instant())
  }
}

data class GetCurrentTimeResponsePayload(val currentTime: Instant) : SuccessResponsePayload
