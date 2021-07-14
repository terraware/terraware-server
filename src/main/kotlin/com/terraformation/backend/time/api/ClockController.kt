package com.terraformation.backend.seedbank.api

import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.time.DatabaseBackedClock
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.validation.constraints.Min
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController

@RestController
@SeedBankAppEndpoint
class ClockController(private val clock: DatabaseBackedClock) {
  @GetMapping("/api/v1/seedbank/clock")
  @Operation(
      summary = "Get the server's current date and time.",
      description =
          "In test environments, the clock can be advanced artificially, which will cause it to " +
              "differ from the real-world date and time.")
  fun getCurrentTime() = GetCurrentTimeResponsePayload(clock.instant())
}

@Controller
@Profile("default", "apidoc")
@SeedBankAppEndpoint
class ClockAdjustmentController(private val clock: DatabaseBackedClock) {
  @GetMapping("/api/test/clock")
  @Hidden
  fun getClockTestUi(model: Model): String {
    val now = ZonedDateTime.now(clock)
    val currentTime = DateTimeFormatter.RFC_1123_DATE_TIME.format(now)
    model.addAttribute("currentTime", currentTime)
    return "test/clock"
  }

  @PostMapping("/api/test/clock")
  @Hidden
  fun getClockTestUi(@RequestParam days: Long, model: Model): String {
    clock.advance(Duration.ofDays(days))
    val daysWord = if (days == 1L) "day" else "days"
    model.addAttribute("successMessage", "Clock advanced by $days $daysWord.")
    return getClockTestUi(model)
  }

  @ApiResponse(
      responseCode = "200",
      description = "The clock has been advanced. The response includes the newly-adjusted time.")
  @Operation(
      summary = "Advance the server's clock.",
      description =
          "Advancing the clock causes any scheduled processes to run. Subsequent GET requests to " +
              "read the current time will take the advancement into account. Only supported in " +
              "test and development environments.")
  @PostMapping("/api/v1/seedbank/clock/advance")
  @ResponseBody
  fun advanceClock(
      @RequestBody payload: AdvanceClockRequestPayload
  ): GetCurrentTimeResponsePayload {
    clock.advance(Duration.ofDays(payload.days.toLong()))
    return GetCurrentTimeResponsePayload(clock.instant())
  }
}

data class GetCurrentTimeResponsePayload(val currentTime: Instant) : SuccessResponsePayload

data class AdvanceClockRequestPayload(@Min(1) val days: Int)
