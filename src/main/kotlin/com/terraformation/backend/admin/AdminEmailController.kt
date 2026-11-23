package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.TerraformationContactMissingEvent
import com.terraformation.backend.db.UserNotFoundForEmailException
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.email.EmailService
import com.terraformation.backend.email.model.DocumentsUpdate
import com.terraformation.backend.email.model.GenericEmail
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.support.SupportService
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
    private val organizationStore: OrganizationStore,
    private val supportService: SupportService,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  @GetMapping("/email")
  fun getSendTestEmailPage(model: Model): String {
    model.addAttribute("isEmailEnabled", config.email.enabled)
    model.addAttribute("isAtlassianEnabled", config.atlassian.enabled)
    if (config.atlassian.enabled) {
      model.addAttribute(
          "organizationsMissingContact",
          organizationStore.fetchWithMissingTerraformationContact().sortedBy {
            it.name.lowercase()
          },
      )
    }

    return "/admin/email"
  }

  @PostMapping("/sendMissingContactNotification")
  fun sendMissingContactNotification(
      @RequestParam organizationId: OrganizationId,
      redirectAttributes: RedirectAttributes,
  ): String {
    if (!config.atlassian.enabled) {
      redirectAttributes.failureMessage = "Jira integration is not enabled."
      return "redirect:/admin/email"
    }

    try {
      val organization =
          organizationStore.fetchWithMissingTerraformationContact(organizationId).singleOrNull()
      if (organization == null) {
        redirectAttributes.failureMessage = "This organization is no longer eligible for the test."
      } else {
        val issueKey =
            supportService.fileMissingContactRequest(
                TerraformationContactMissingEvent(
                    organization.id,
                    organization.name,
                    "Test missing-contact notification",
                    "This is a test notification sent from the Terraware admin console.",
                )
            )
        if (issueKey == null) {
          redirectAttributes.failureMessage =
              "Could not create the test Jira ticket. Check the server logs for details."
        } else {
          redirectAttributes.successMessage = "Created test Jira ticket $issueKey."
        }
      }
    } catch (e: Exception) {
      log.error(
          "Failed to send test missing-contact notification for organization $organizationId",
          e,
      )
      redirectAttributes.failureMessage = "Could not send the test notification."
    }

    return "redirect:/admin/email"
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
