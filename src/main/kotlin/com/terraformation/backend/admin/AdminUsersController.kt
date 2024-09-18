package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.log.perClassLogger
import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminUsersController(
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  @PostMapping("/users/delete")
  fun deleteUser(
      @NotBlank @RequestParam email: String,
      @RequestParam confirm: Boolean?,
      redirectAttributes: RedirectAttributes,
  ): String {
    if (confirm == null || !confirm) {
      redirectAttributes.failureMessage = "Delete user confirmation checkbox is unchecked"
      return redirectToAdminHome()
    }
    try {
      val user = userStore.fetchByEmail(email)
      if (user != null) {
        userStore.deleteUserById(user.userId)
        redirectAttributes.successMessage = "User ${user.userId} deleted"
      } else {
        redirectAttributes.failureMessage = "User $email does not exist"
      }
    } catch (e: Exception) {
      log.warn("Failed to delete user", e)
      redirectAttributes.failureMessage = "Deleting user failed: ${e.message}"
    }

    return redirectToAdminHome()
  }

  private fun redirectToAdminHome() = "redirect:/admin/"
}
