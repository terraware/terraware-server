package com.terraformation.backend.admin

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
class AdminGlobalRolesController(
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  @GetMapping("/globalRoles")
  fun getGlobalRoles(
      model: Model,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateGlobalRoles() }

    model.addAttribute("globalRoles", GlobalRole.entries.sortedBy { it.jsonValue })
    model.addAttribute("users", userStore.fetchWithGlobalRoles())

    return "/admin/globalRoles"
  }

  @PostMapping("/globalRoles")
  fun updateGlobalRoles(
      @RequestParam userId: UserId?,
      @RequestParam email: String?,
      @RequestParam roles: List<String>?,
      redirectAttributes: RedirectAttributes,
  ): String {
    val roleEnums = roles?.map { GlobalRole.forJsonValue(it) }?.toSet() ?: emptySet()

    val effectiveUserId =
        when {
          userId != null -> userId
          email != null -> {
            val userIdForEmail = userStore.fetchByEmail(email)?.userId
            if (userIdForEmail == null) {
              redirectAttributes.failureMessage = "$email not found."
              return redirectToGlobalRoles()
            }
            userIdForEmail
          }
          else -> {
            redirectAttributes.failureMessage = "No user specified."
            return redirectToGlobalRoles()
          }
        }

    try {
      userStore.updateGlobalRoles(effectiveUserId, roleEnums)
      redirectAttributes.successMessage = "Global roles updated."
    } catch (e: Exception) {
      log.error("Failed to update global roles", e)
      redirectAttributes.failureMessage = "Failed to update global roles: ${e.message}"
    }

    return redirectToGlobalRoles()
  }

  private fun redirectToGlobalRoles() = "redirect:/admin/globalRoles"
}
