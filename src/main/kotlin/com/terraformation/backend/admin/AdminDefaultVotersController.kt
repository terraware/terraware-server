package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.DefaultVoterStore
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.log.perClassLogger
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
class AdminDefaultVotersController(
    private val userStore: UserStore,
    private val defaultVoterStore: DefaultVoterStore,
) {
  private val log = perClassLogger()

  @GetMapping("/defaultVoters")
  fun getDefaultVoters(
      model: Model,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateGlobalRoles() }

    model.addAttribute("users", defaultVoterStore.findAll().map { userStore.fetchOneById(it) })

    return "/admin/defaultVoters"
  }

  @PostMapping("/defaultVoters/add")
  fun addDefaultVoter(
      @RequestParam email: String,
      redirectAttributes: RedirectAttributes,
  ): String {

    val userIdForEmail = userStore.fetchByEmail(email)?.userId
    if (userIdForEmail == null) {
      redirectAttributes.failureMessage = "$email not found."
      return redirectToDefaultVoter()
    }

    try {
      defaultVoterStore.insert(userIdForEmail)
      redirectAttributes.successMessage = "Default voter added."
    } catch (e: Exception) {
      log.error("Failed to add default voter", e)
      redirectAttributes.failureMessage = "Failed to add default voter: ${e.message}"
    }

    return redirectToDefaultVoter()
  }

  @PostMapping("/defaultVoters/remove")
  fun removeDefaultVoter(
      @RequestParam userId: UserId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      defaultVoterStore.delete(userId)
      redirectAttributes.successMessage = "Default voter removed."
    } catch (e: Exception) {
      log.error("Failed to add default voter", e)
      redirectAttributes.failureMessage = "Failed to remove default voter: ${e.message}"
    }

    return redirectToDefaultVoter()
  }

  private fun redirectToDefaultVoter() = "redirect:/admin/defaultVoters"
}
