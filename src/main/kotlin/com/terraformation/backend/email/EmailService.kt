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
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.processToString
import freemarker.template.Configuration
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

    val projectId =
        parentStore.getProjectId(facilityId) ?: throw FacilityNotFoundException(facilityId)
    val recipients = projectStore.fetchEmailRecipients(projectId)

    if (recipients.isEmpty()) {
      log.error("Got alert for facility $facilityId but no recipients are configured")
      log.info("Alert subject: $subject")
      log.info("Alert body: $textBody")
    }

    val message = sender.createMimeMessage()
    val helper = MimeMessageHelper(message)

    helper.setSubject(subject)
    helper.setTo(recipients.toTypedArray())
    helper.setText(textBody)

    send(message)
  }

  fun sendIdleFacilityAlert(facilityId: FacilityId) {
    requirePermissions { sendAlert(facilityId) }

    val projectId =
        parentStore.getProjectId(facilityId) ?: throw FacilityNotFoundException(facilityId)
    val recipients = projectStore.fetchEmailRecipients(projectId)

    if (recipients.isEmpty()) {
      log.warn("No alert recipients for idle facility $facilityId")
      return
    }

    val facility =
        facilityStore.fetchById(facilityId) ?: throw FacilityNotFoundException(facilityId)

    val model =
        mapOf(
            "facility" to facility,
            "lastTimeseriesTime" to messages.dateAndTime(facility.lastTimeseriesTime))
    val textBody =
        freeMarkerConfig.getTemplate("email/facilityIdle/body.txt.ftl").processToString(model)

    val message = sender.createMimeMessage()
    val helper = MimeMessageHelper(message)

    helper.setSubject(messages.facilityIdleSubject(facility.name))
    helper.setText(textBody)
    helper.setTo(recipients.toTypedArray())

    send(message)
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

    val message = sender.createMimeMessage()
    val helper = MimeMessageHelper(message, true)

    val webAppUrl = "${config.webAppUrl}".trimEnd('/')
    val organizationHomeUrl = webAppUrls.organizationHome(organizationId).toString()

    val model =
        mapOf(
            "admin" to admin,
            "organization" to organization,
            "organizationHomeUrl" to organizationHomeUrl,
            "webAppUrl" to webAppUrl,
        )

    val textBody =
        freeMarkerConfig
            .getTemplate("email/userAddedToOrganization/body.txt.ftl")
            .processToString(model)
    val htmlBody =
        freeMarkerConfig
            .getTemplate("email/userAddedToOrganization/body.ftlh")
            .processToString(model)

    helper.setSubject(messages.userAddedToOrganizationSubject(admin.fullName, organization.name))
    helper.setTo(user.email)
    helper.setText(textBody, htmlBody)

    send(message)
  }

  /** Sends an email message. Overrides the recipient and subject line in dev/test environments. */
  private fun send(message: MimeMessage) {
    val helper = MimeMessageHelper(message)

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
