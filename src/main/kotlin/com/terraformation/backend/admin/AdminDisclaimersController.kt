package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.db.DisclaimerStore
import com.terraformation.backend.db.default_schema.DisclaimerId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.daos.UsersDao
import com.terraformation.backend.log.perClassLogger
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneOffset
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminDisclaimersController(
    private val clock: InstantSource,
    private val disclaimerStore: DisclaimerStore,
    private val usersDao: UsersDao,
) {
  private val log = perClassLogger()

  @GetMapping("/disclaimers")
  fun getDisclaimers(model: Model): String {
    val disclaimers = disclaimerStore.fetchAllDisclaimers()
    val currentDisclaimerId =
        disclaimers.firstOrNull { it.effectiveOn.isBefore(clock.instant()) }?.id

    model.addAttribute("currentDisclaimerId", currentDisclaimerId)
    model.addAttribute("disclaimers", disclaimers)

    return "/admin/disclaimers"
  }

  @GetMapping("/disclaimers/{id}")
  fun getOneDisclaimer(@PathVariable id: DisclaimerId, model: Model): String {
    val disclaimer = disclaimerStore.fetchOneDisclaimer(id)
    val funderEmails =
        usersDao
            .findAll()
            .filter { it.userTypeId == UserType.Funder }
            .associate { it.id to it.email }

    model.addAttribute("disclaimer", disclaimer)
    model.addAttribute("funderEmails", funderEmails)

    return "/admin/disclaimer"
  }

  @PostMapping("/disclaimers")
  fun addDisclaimer(
      @RequestParam content: String,
      @RequestParam effectiveDate: LocalDate,
      redirectAttributes: RedirectAttributes,
  ): String {
    val effectiveOn = effectiveDate.atStartOfDay(ZoneOffset.UTC).toInstant()

    try {
      disclaimerStore.createDisclaimer(content, effectiveOn)
      redirectAttributes.successMessage = "New disclaimer created."
    } catch (e: Exception) {
      log.error("Failed to create disclaimer", e)
      redirectAttributes.failureMessage = "Failed to create disclaimer: ${e.message}"
    }

    return redirectToDisclaimers()
  }

  @PostMapping("/disclaimers/{id}/delete")
  fun deleteDisclaimer(
      @PathVariable id: DisclaimerId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      disclaimerStore.deleteDisclaimer(id)
      redirectAttributes.successMessage = "Deleted disclaimer."
    } catch (e: Exception) {
      log.error("Failed to delete disclaimer $id", e)
      redirectAttributes.failureMessage = "Failed to delete disclaimer: ${e.message}"
    }

    return redirectToDisclaimers()
  }

  @PostMapping("/disclaimers/{id}/deleteAcceptance/{userId}")
  fun deleteDisclaimerAcceptance(
      @PathVariable id: DisclaimerId,
      @PathVariable userId: UserId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      disclaimerStore.deleteDisclaimerAcceptance(id, userId)
      redirectAttributes.successMessage = "Deleted disclaimer acceptance."
    } catch (e: Exception) {
      log.error("Failed to delete disclaimer $id acceptance for user $userId", e)
      redirectAttributes.failureMessage = "Failed to delete disclaimer acceptance: ${e.message}"
    }

    return redirectToDisclaimer(id)
  }

  private fun redirectToDisclaimer(id: DisclaimerId) = "redirect:/admin/disclaimers/$id"

  private fun redirectToDisclaimers() = "redirect:/admin/disclaimers"
}
