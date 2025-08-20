package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.time.DatabaseBackedClock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminTestClockController(
    private val clock: DatabaseBackedClock,
    private val config: TerrawareServerConfig,
) {
  @GetMapping("/testClock")
  fun getTestClock(model: Model, redirectAttributes: RedirectAttributes): String {
    if (!config.useTestClock) {
      redirectAttributes.failureMessage = "Test clock is not enabled."
      return redirectToAdminHome()
    }

    val now = ZonedDateTime.now(clock)
    val currentTime = DateTimeFormatter.RFC_1123_DATE_TIME.format(now)
    model.addAttribute("currentTime", currentTime)

    return "/admin/testClock"
  }

  @PostMapping("/advanceTestClock")
  fun advanceTestClock(
      @RequestParam quantity: Long,
      @RequestParam units: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    val duration =
        when (units) {
          "M" -> Duration.ofMinutes(quantity)
          "H" -> Duration.ofHours(quantity)
          "D" -> Duration.ofDays(quantity)
          else -> Duration.parse("PT$quantity$units")
        }
    val prettyDuration =
        when (units) {
          "M" -> if (quantity == 1L) "1 minute" else "$quantity minutes"
          "H" -> if (quantity == 1L) "1 hour" else "$quantity hours"
          "D" -> if (quantity == 1L) "1 day" else "$quantity days"
          else -> "$duration"
        }

    clock.advance(duration)

    redirectAttributes.successMessage = "Clock advanced by $prettyDuration."
    return redirectToTestClock()
  }

  @PostMapping("/resetTestClock")
  fun resetTestClock(redirectAttributes: RedirectAttributes): String {
    clock.reset()
    redirectAttributes.successMessage = "Test clock reset to real time and date."
    return redirectToTestClock()
  }

  private fun redirectToAdminHome() = "redirect:/admin/"

  private fun redirectToTestClock() = "redirect:/admin/testClock"
}
