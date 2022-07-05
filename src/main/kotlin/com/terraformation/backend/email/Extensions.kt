package com.terraformation.backend.email

import javax.mail.Message
import javax.mail.internet.MimeMessage

/** Returns the list of recipients of a certain type as strings. */
fun MimeMessage.getRecipientsString(type: Message.RecipientType): List<String> {
  return getRecipients(type)?.map { "$it" } ?: emptyList()
}

/** Returns the list of recipients as a single string suitable for use in a log message. */
fun MimeMessage.getAllRecipientsString(): String {
  return "To: ${getRecipientsString(Message.RecipientType.TO)} " +
      "Cc: ${getRecipientsString(Message.RecipientType.CC)} " +
      "Bcc: ${getRecipientsString(Message.RecipientType.BCC)}"
}
