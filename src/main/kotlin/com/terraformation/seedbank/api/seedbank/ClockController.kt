package com.terraformation.seedbank.api.seedbank

import com.terraformation.seedbank.api.SuccessResponsePayload
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.services.DatabaseBackedClock
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Duration
import java.time.Instant
import javax.validation.constraints.Min
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Profile("default", "apidoc")
@RequestMapping("/api/v1/seedbank/clock")
@RestController
@SeedBankAppEndpoint
class ClockController(private val clock: DatabaseBackedClock) {
  @GetMapping
  @Operation(
      summary = "Get the server's current date and time.",
      description =
          "In test environments, the clock can be advanced artificially, which will cause it to " +
              "differ from the real-world date and time.")
  fun getCurrentTime() = GetCurrentTimeResponsePayload(clock.instant())

  @ApiResponse(
      responseCode = "200",
      description = "The clock has been advanced. The response includes the newly-adjusted time.")
  @Operation(
      summary = "Advance the server's clock.",
      description =
          "Advancing the clock causes any scheduled processes to run. Subsequent GET requests to " +
              "read the current time will take the advancement into account. Only supported in " +
              "test and development environments.")
  @PostMapping("/advance")
  fun advanceClock(
      @RequestBody payload: AdvanceClockRequestPayload
  ): GetCurrentTimeResponsePayload {
    clock.advance(Duration.ofDays(payload.days.toLong()))
    return GetCurrentTimeResponsePayload(clock.instant())
  }
}

data class GetCurrentTimeResponsePayload(val currentTime: Instant) : SuccessResponsePayload

data class AdvanceClockRequestPayload(@Min(1) val days: Int)
