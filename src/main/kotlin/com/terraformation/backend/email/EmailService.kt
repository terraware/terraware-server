package com.terraformation.backend.email

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.email.model.EmailTemplateModel
import com.terraformation.backend.email.model.FacilityAlert
import com.terraformation.backend.email.model.FacilityIdle
import com.terraformation.backend.email.model.UserAddedToOrganization
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.processToString
import freemarker.template.Configuration
import freemarker.template.TemplateNotFoundException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import javax.annotation.ManagedBean
import javax.mail.Message
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import org.apache.commons.validator.routines.EmailValidator
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.security.access.AccessDeniedException
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.Destination
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.RawMessage

/**
 * Renders email messages from templates and sends them to people using the configured mail server.
 *
 * Email templates are loaded from the `src/main/resources/templates/email` directory. Please see
 * the README file in that directory for more details.
 */
@ManagedBean
class EmailService(
    private val config: TerrawareServerConfig,
    private val facilityStore: FacilityStore,
    private val freeMarkerConfig: Configuration,
    private val messages: Messages,
    private val organizationStore: OrganizationStore,
    private val parentStore: ParentStore,
    private val projectStore: ProjectStore,
    private val sender: JavaMailSender,
    private val userStore: UserStore,
    private val webAppUrls: WebAppUrls,
) {
  private lateinit var sesClient: SesV2Client
  private val emailValidator = EmailValidator.getInstance()
  private val log = perClassLogger()

  init {
    if (config.email.enabled && config.email.useSes) {
      sesClient = SesV2Client.create()
    }
  }

  /**
   * Sends an alert about a facility.
   *
   * This is a placeholder implementation for use by the device manager.
   */
  fun sendAlert(facilityId: FacilityId, subject: String, textBody: String) {
    requirePermissions { sendAlert(facilityId) }

    val facility =
        facilityStore.fetchById(facilityId) ?: throw FacilityNotFoundException(facilityId)

    sendFacilityNotification(
        facilityId, "facilityAlert", FacilityAlert(textBody, facility, currentUser(), subject))
  }

  fun sendIdleFacilityAlert(facilityId: FacilityId) {
    requirePermissions { sendAlert(facilityId) }

    val facility =
        facilityStore.fetchById(facilityId) ?: throw FacilityNotFoundException(facilityId)

    sendFacilityNotification(
        facilityId,
        "facilityIdle",
        FacilityIdle(facility, messages.dateAndTime(facility.lastTimeseriesTime)))
  }

  fun sendUserAddedToOrganization(organizationId: OrganizationId, userId: UserId) {
    requirePermissions { addOrganizationUser(organizationId) }

    val admin =
        currentUser() as? IndividualUser
            ?: throw AccessDeniedException("Notification email must be sent by a regular user")

    val organization =
        organizationStore.fetchById(organizationId)
            ?: throw OrganizationNotFoundException(organizationId)
    val user = userStore.fetchById(userId) ?: throw UserNotFoundException(userId)

    val webAppUrl = "${config.webAppUrl}".trimEnd('/')
    val organizationHomeUrl = webAppUrls.organizationHome(organizationId).toString()

    val model = UserAddedToOrganization(admin, organization, organizationHomeUrl, webAppUrl)

    sendUserNotification(user, "userAddedToOrganization", model, false)
  }

  /**
   * Sends an email notification to all the people who should be notified about something happening
   * at or to a particular facility.
   *
   * @param [templateDir] Subdirectory of `src/main/resources/templates/email` containing the
   * Freemarker templates to render.
   * @param [model] Model object containing values that can be referenced by the template.
   * @param [requireOptIn] If false, send the notification to all eligible users, even if they have
   * opted out of email notifications. The default is to obey the user's notification preference,
   * which is the correct thing to do in the vast majority of cases.
   */
  fun sendFacilityNotification(
      facilityId: FacilityId,
      templateDir: String,
      model: EmailTemplateModel,
      requireOptIn: Boolean = true
  ) {
    val projectId =
        parentStore.getProjectId(facilityId) ?: throw FacilityNotFoundException(facilityId)

    sendProjectNotification(projectId, templateDir, model, requireOptIn)
  }

  /**
   * Sends an email notification to all the people who should be notified about something happening
   * to a particular project.
   *
   * @param [templateDir] Subdirectory of `src/main/resources/templates/email` containing the
   * Freemarker templates to render.
   * @param [model] Model object containing values that can be referenced by the template.
   * @param [requireOptIn] If false, send the notification to all eligible users, even if they have
   * opted out of email notifications. The default is to obey the user's notification preference,
   * which is the correct thing to do in the vast majority of cases.
   */
  fun sendProjectNotification(
      projectId: ProjectId,
      templateDir: String,
      model: EmailTemplateModel,
      requireOptIn: Boolean = true
  ) {
    val recipients = projectStore.fetchEmailRecipients(projectId, requireOptIn)

    send(templateDir, model, recipients)
  }

  /**
   * Sends an email notification to all the people who should be notified about something happening
   * to a particular organization.
   *
   * @param [templateDir] Subdirectory of `src/main/resources/templates/email` containing the
   * Freemarker templates to render.
   * @param [model] Model object containing values that can be referenced by the template.
   * @param [requireOptIn] If false, send the notification to all eligible users, even if they have
   * opted out of email notifications. The default is to obey the user's notification preference,
   * which is the correct thing to do in the vast majority of cases.
   */
  fun sendOrganizationNotification(
      organizationId: OrganizationId,
      templateDir: String,
      model: EmailTemplateModel,
      requireOptIn: Boolean = true,
  ) {
    val recipients = organizationStore.fetchEmailRecipients(organizationId, requireOptIn)

    send(templateDir, model, recipients)
  }

  /**
   * Sends an email notification to a specific user.
   *
   * @param [templateDir] Subdirectory of `src/main/resources/templates/email` containing the
   * Freemarker templates to render.
   * @param [model] Model object containing values that can be referenced by the template.
   * @param [requireOptIn] If false, send the notification even if the user has not opted into email
   * notifications. The default is to obey the user's notification preference, which is the correct
   * thing to do in the majority of cases.
   */
  fun sendUserNotification(
      user: IndividualUser,
      templateDir: String,
      model: EmailTemplateModel,
      requireOptIn: Boolean = true
  ) {
    if (requireOptIn && !user.emailNotificationsEnabled) {
      log.info("Skipping email notification for user ${user.userId} because they didn't enable it")
    } else {
      send(templateDir, model, listOf(user.email))
    }
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
   * @param [templateDir] Subdirectory of `src/main/resources/templates/email` containing the
   * Freemarker templates to render.
   * @param [model] Model object containing values that can be referenced by the template.
   * @param [recipients] Email addresses to send the message to. This will be overridden in dev/test
   * environments when [TerrawareServerConfig.EmailConfig.alwaysSendToOverrideAddress] is true.
   */
  private fun send(templateDir: String, model: EmailTemplateModel, recipients: List<String>) {
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

    helper.setSubject(subject)

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
                  "BUG! Override email address is null; should have been caught at start time"))

      log.debug(
          "Override address $overrideEmailAddress replacing ${message.getAllRecipientsString()}")

      helper.setTo(overrideEmailAddress)
      helper.setCc(emptyArray<InternetAddress>())
      helper.setBcc(emptyArray<InternetAddress>())
    }

    if (config.email.subjectPrefix != null) {
      helper.setSubject("${config.email.subjectPrefix} ${message.subject}")
    }

    val rawMessage =
        ByteArrayOutputStream().use { stream ->
          message.writeTo(stream)
          stream.toByteArray()
        }

    // If we're not sending the message to real recipients, log the whole thing since we're probably
    // in a dev environment. But for real messages, just log the recipients and subjects since the
    // body could contain sensitive information.

    if (!config.email.enabled || config.email.alwaysSendToOverrideAddress) {
      log.info("Generated email: ${rawMessage.toString(StandardCharsets.UTF_8)}")
    }

    if (config.email.enabled) {
      try {
        val messageId =
            if (config.email.useSes) {
              sendSes(message, rawMessage)
            } else {
              sendSmtp(message)
            }

        log.info(
            "Sent email $messageId with subject \"${message.subject}\" ${message.getAllRecipientsString()}")
      } catch (e: Exception) {
        // TODO: Queue the message to be retried later.

        // We're going to let the exception bubble up to the caller, which can log the stack trace
        // if appropriate, but the caller won't have access to the recipient list or subject since
        // both could have been overridden above.
        log.info(
            "Failed to send email with subject \"${message.subject}\" ${message.getAllRecipientsString()}")

        throw e
      }
    } else {
      log.info("Email sending is disabled; did not send the generated message.")
    }
  }

  /** Sends the message using the SES SendEmail API. */
  private fun sendSes(message: MimeMessage, rawMessage: ByteArray): String? {
    val sdkBytes = SdkBytes.fromByteArrayUnsafe(rawMessage)
    val sender =
        message.from?.getOrNull(0)?.toString()
            ?: throw IllegalArgumentException("No sender address specified")

    val response =
        sesClient.sendEmail { builder ->
          builder.fromEmailAddress(sender)
          builder.destination(
              Destination.builder()
                  .toAddresses(message.getRecipientsString(Message.RecipientType.TO))
                  .ccAddresses(message.getRecipientsString(Message.RecipientType.CC))
                  .bccAddresses(message.getRecipientsString(Message.RecipientType.BCC))
                  .build(),
          )
          builder.content(
              EmailContent.builder().raw(RawMessage.builder().data(sdkBytes).build()).build())
        }

    return response.messageId()
  }

  /** Sends the message using SMTP. */
  private fun sendSmtp(message: MimeMessage): String? {
    sender.send(message)
    return message.messageID
  }

  /** Returns the list of recipients of a certain type as strings. */
  private fun MimeMessage.getRecipientsString(type: Message.RecipientType): List<String> {
    return getRecipients(type)?.map { "$it" } ?: emptyList()
  }

  /** Returns the list of recipients as a single string suitable for use in a log message. */
  private fun MimeMessage.getAllRecipientsString(): String {
    return "To: ${getRecipientsString(Message.RecipientType.TO)} " +
        "Cc: ${getRecipientsString(Message.RecipientType.CC)} " +
        "Bcc: ${getRecipientsString(Message.RecipientType.BCC)}"
  }
}
