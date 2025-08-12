package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.UserNotFoundForEmailException
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.email.EmailService
import com.terraformation.backend.email.model.DocumentsUpdate
import com.terraformation.backend.email.model.GenericEmail
import com.terraformation.backend.log.perClassLogger
import jakarta.validation.constraints.NotBlank
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
class AdminEmailController(
    private val config: TerrawareServerConfig,
    private val emailService: EmailService,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  @GetMapping("/email")
  fun getSendTestEmailPage(model: Model): String {
    model.addAttribute("isEmailEnabled", config.email.enabled)

    return "/admin/email"
  }

  @PostMapping("/sendEmail")
  fun sendEmail(
      @NotBlank @RequestParam emailName: String,
      @RequestParam recipient: String?,
      @RequestParam sendToAll: Boolean?,
      @RequestParam emailBody: String?,
      @RequestParam subject: String?,
      redirectAttributes: RedirectAttributes,
  ): String {

    val emailNameResult =
        when (emailName) {
          "DocumentsUpdate" -> DocumentsUpdate(config)
          "GenericEmail" -> {
            if (emailBody == null || subject == null) {
              throw IllegalArgumentException("emailBody and subject are required for GenericEmail")
            }
            GenericEmail(config, emailBody, subject)
          }
          else -> throw IllegalArgumentException("Invalid test email name $emailName")
        }

    try {
      if (sendToAll == true) {
        emailService.sendAllUsersNotification(
            emailNameResult,
            false,
        )
      } else if (recipient != null) {
        val user =
            userStore.fetchByEmail(recipient) ?: throw UserNotFoundForEmailException(recipient)
        emailService.sendUserNotification(
            user,
            emailNameResult,
            false,
        )
      }

      redirectAttributes.successMessage =
          if (config.email.enabled) {
            "Test email sent."
          } else {
            "Email sending is currently disabled."
          }
    } catch (e: Exception) {
      log.error("Failed to send alert", e)
      redirectAttributes.failureMessage = "Failed to send test email: ${e.message}"
    }

    return "redirect:/admin/email"
  }
}
