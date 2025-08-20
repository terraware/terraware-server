package com.terraformation.backend.email

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.email.model.EmailTemplateModel
import com.terraformation.backend.i18n.use
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.processToString
import freemarker.template.Configuration
import freemarker.template.TemplateNotFoundException
import jakarta.inject.Named
import jakarta.mail.internet.InternetAddress
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.apache.commons.validator.routines.EmailValidator
import org.springframework.mail.javamail.MimeMessageHelper

/**
 * Renders email messages from templates and sends them to people using the configured mail server.
 *
 * Email templates are loaded from the `src/main/resources/templates/email` directory. Please see
 * the README file in that directory for more details.
 *
 * This class does not interact with any email services; that's done in [EmailSender].
 */
@Named
class EmailService(
    private val config: TerrawareServerConfig,
    private val freeMarkerConfig: Configuration,
    private val parentStore: ParentStore,
    private val sender: EmailSender,
    private val userStore: UserStore,
) {
  private val emailValidator = EmailValidator.getInstance()
  private val log = perClassLogger()

  companion object {
    val defaultOrgRolesForNotification: Set<Role> =
        Role.values().filter { it != Role.TerraformationContact }.toSet()
  }

  /**
   * Sends an email notification to all the people who should be notified about something happening
   * at or to a particular facility.
   *
   * @param [model] Model object containing values that can be referenced by the template.
   * @param [requireOptIn] If false, send the notification to all eligible users, even if they have
   *   opted out of email notifications. The default is to obey the user's notification preference,
   *   which is the correct thing to do in the vast majority of cases.
   */
  fun sendFacilityNotification(
      facilityId: FacilityId,
      model: EmailTemplateModel,
      requireOptIn: Boolean = true,
  ) {
    val organizationId =
        parentStore.getOrganizationId(facilityId) ?: throw FacilityNotFoundException(facilityId)

    sendOrganizationNotification(organizationId, model, requireOptIn)
  }

  /**
   * Sends an email notification to all the funders within a funding entity
   *
   * @param [model] Model object containing values that can be referenced by the template.
   * @param [requireOptIn] If false, send the notification to all eligible users, even if they have
   *   opted out of email notifications. The default is to obey the user's notification preference,
   *   which is the correct thing to do in the vast majority of cases.
   */
  fun sendFundingEntityNotification(
      fundingEntityId: FundingEntityId,
      model: EmailTemplateModel,
      requireOptIn: Boolean = true,
  ) {
    userStore.fetchByFundingEntityId(fundingEntityId).forEach { user ->
      sendUserNotification(user, model, requireOptIn)
    }
  }

  /**
   * Sends an email notification to all the people who should be notified about something happening
   * to a particular organization.
   *
   * @param [model] Model object containing values that can be referenced by the template.
   * @param [requireOptIn] If false, send the notification to all eligible users, even if they have
   *   opted out of email notifications. The default is to obey the user's notification preference,
   *   which is the correct thing to do in the vast majority of cases.
   * @param [roles] Only those members with matching roles will receive the notification. By default
   *   all member roles except 'Terraformation Contact' will receive the notification.
   */
  fun sendOrganizationNotification(
      organizationId: OrganizationId,
      model: EmailTemplateModel,
      requireOptIn: Boolean = true,
      roles: Set<Role> = defaultOrgRolesForNotification,
  ) {
    userStore.fetchByOrganizationId(organizationId, requireOptIn, roles).forEach { user ->
      sendUserNotification(user, model, requireOptIn)
    }
  }

  /**
   * Sends an email notification to a specific user.
   *
   * @param [model] Model object containing values that can be referenced by the template.
   * @param [requireOptIn] If false, send the notification even if the user has not opted into email
   *   notifications. The default is to obey the user's notification preference, which is the
   *   correct thing to do in the majority of cases.
   */
  fun sendUserNotification(
      user: TerrawareUser,
      model: EmailTemplateModel,
      requireOptIn: Boolean = true,
  ) {
    if (requireOptIn && !user.emailNotificationsEnabled) {
      log.info("Skipping email notification for user ${user.userId} because they didn't enable it")
    } else {
      sendLocaleEmails(model, listOf(user.email), user.locale)
    }
  }

  /**
   * Sends an email notification to all users.
   *
   * @param [model] Model object containing values that can be referenced by the template.
   * @param [requireOptIn] If false, send the notification even if the user has not opted into email
   *   notifications. The default is to obey the user's notification preference, which is the
   *   correct thing to do in the majority of cases.
   */
  fun sendAllUsersNotification(
      model: EmailTemplateModel,
      requireOptIn: Boolean = true,
  ) {
    val allUsers = userStore.fetchUsers(requireOptIn)
    allUsers.forEach { user ->
      try {
        sendLocaleEmails(model, listOf(user.email), user.locale)
      } catch (e: Exception) {
        log.error("Failed to send email to user ${user.userId}: ${e.message}")
      }
    }
  }

  /**
   * Sends an email notification to a support email if configured.
   *
   * @param [model] Model object containing values that can be referenced by the template.
   */
  fun sendSupportNotification(model: EmailTemplateModel) {
    config.support.email?.let { supportEmail -> sendLocaleEmails(model, listOf(supportEmail)) }
  }

  /**
   * Sends an email notification to a specific list of emails, using the specified Locale.
   *
   * @param [recipients] Email addresses to send the message to.
   * @param [model] Model object containing values that can be referenced by the template.
   * @param [initialLocale] Optional Locale object; uses ENGLISH if unspecified.
   */
  fun sendLocaleEmails(
      model: EmailTemplateModel,
      recipients: List<String>,
      initialLocale: Locale? = null,
  ) {
    val finalLocale = initialLocale ?: Locale.ENGLISH
    finalLocale.use { send(model, recipients) }
  }

  /** Renders a Freemarker template if it exists. Returns null if the template doesn't exist. */
  private fun renderOptionalTemplate(path: String, model: EmailTemplateModel): String? {
    // Set the ignoreMissing flag which causes getTemplate() to return null if the template
    // doesn't exist.
    return freeMarkerConfig.getTemplate(path, null, null, true, true)?.processToString(model)
  }

  /**
   * Renders a Freemarker template.
   *
   * @throws TemplateNotFoundException The template does not exist.
   */
  private fun renderRequiredTemplate(path: String, model: EmailTemplateModel): String {
    return freeMarkerConfig.getTemplate(path).processToString(model)
  }

  /**
   * Renders an email message from a template and sends it to some recipients.
   *
   * @param [model] Model object containing values that can be referenced by the template.
   * @param [recipients] Email addresses to send the message to. This will be overridden in dev/test
   *   environments when [TerrawareServerConfig.EmailConfig.alwaysSendToOverrideAddress] is true.
   */
  private fun send(model: EmailTemplateModel, recipients: List<String>) {
    val templateDir = model.templateDir

    if (recipients.isEmpty()) {
      log.info("No recipients found for email notification $templateDir, so not sending any email.")
      // Don't log the contents of the email; it may contain sensitive information.
      return
    }

    val subject = renderRequiredTemplate("email/$templateDir/subject.ftl", model).trim()
    val textBody = renderOptionalTemplate("email/$templateDir/body.txt.ftl", model)
    val htmlBody = renderOptionalTemplate("email/$templateDir/body.ftlh", model)

    val multipart = textBody != null && htmlBody != null
    val helper = MimeMessageHelper(sender.createMimeMessage(), multipart)

    if (config.email.subjectPrefix != null) {
      helper.setSubject("${config.email.subjectPrefix} $subject")
    } else {
      helper.setSubject(subject)
    }

    when {
      textBody != null && htmlBody != null -> helper.setText(textBody, htmlBody)
      textBody != null -> helper.setText(textBody, false)
      htmlBody != null -> helper.setText(htmlBody, true)
      else -> throw IllegalStateException("No email templates found in $templateDir")
    }

    recipients.forEach { recipient ->
      helper.setTo(recipient)
      send(helper)
    }
  }

  /** Sends an email message. Overrides the recipient and subject line in dev/test environments. */
  private fun send(helper: MimeMessageHelper) {
    val message = helper.mimeMessage

    // Validate the caller-supplied recipient(s) even if we're going to override, so we can test
    // validation in dev environments.
    message.allRecipients.forEach { address ->
      if (!emailValidator.isValid("$address")) {
        throw IllegalArgumentException("Invalid email address $address")
      }
    }

    config.email.senderAddress?.let { senderAddress -> helper.setFrom(senderAddress) }

    if (config.email.alwaysSendToOverrideAddress && config.email.overrideAddress != null) {
      val overrideEmailAddress =
          (config.email.overrideAddress
              ?: throw IllegalStateException(
                  "BUG! Override email address is null; should have been caught at start time"
              ))

      log.debug(
          "Override address $overrideEmailAddress replacing ${message.getAllRecipientsString()}"
      )

      helper.setTo(overrideEmailAddress)
      helper.setCc(emptyArray<InternetAddress>())
      helper.setBcc(emptyArray<InternetAddress>())
    }

    // If we're not sending the message to real recipients, log the whole thing since we're probably
    // in a dev environment. But for real messages, just log the recipients and subjects since the
    // body could contain sensitive information.

    if (!config.email.enabled || config.email.alwaysSendToOverrideAddress) {
      val rawMessage =
          ByteArrayOutputStream().use { stream ->
            message.writeTo(stream)
            stream.toByteArray()
          }

      log.info("Generated email: ${rawMessage.toString(StandardCharsets.UTF_8)}")
    }

    if (config.email.enabled) {
      try {
        val messageId = sender.send(message)

        log.info(
            "Sent email $messageId with subject \"${message.subject}\" ${message.getAllRecipientsString()}"
        )
      } catch (e: Exception) {
        // TODO: Queue the message to be retried later.

        // We're going to let the exception bubble up to the caller, which can log the stack trace
        // if appropriate, but the caller won't have access to the recipient list or subject since
        // both could have been overridden above.
        log.info(
            "Failed to send email with subject \"${message.subject}\" ${message.getAllRecipientsString()}"
        )

        throw e
      }
    } else {
      log.info("Email sending is disabled; did not send the generated message.")
    }
  }
}
