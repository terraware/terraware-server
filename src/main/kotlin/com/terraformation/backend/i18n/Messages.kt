package com.terraformation.backend.i18n

import com.terraformation.backend.db.GerminationTestType
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.annotation.ManagedBean

/** Helper class to encapsulate notification message semantics */
data class NotificationMessage(val title: String, val body: String)

/**
 * Renders human-readable messages. All server-generated text that gets displayed to end users
 * should live here rather than inline in the rest of the application. This will make it easier to
 * localize the messages into languages other than English in future versions.
 */
@ManagedBean
class Messages {
  fun longPendingNotification(count: Int) =
      if (count == 1)
          "1 seed collection bag has been waiting since drop off for at least 1 week and is " +
              "ready to be processed."
      else
          "$count seed collection bags have been waiting since drop off for at least 1 week and " +
              "are ready to be processed."

  fun longProcessedNotification(count: Int, weeks: Int) =
      if (count == 1)
          "1 accession has finished processing for at least $weeks weeks and is ready to be " +
              "tested for %RH (or dried)."
      else
          "$count accessions have finished processing for at least $weeks weeks and are ready to " +
              "be tested for %RH (or dried)."

  fun driedNotification(count: Int) =
      if (count == 1) "1 accession has passed its drying end date and is ready to be stored."
      else "$count accessions have passed their drying end date and are ready to be stored."

  fun dryingMoveDateNotification(accessionNumber: String) =
      "$accessionNumber is scheduled to be moved from racks to dry cabinets today!"

  fun germinationTestDateNotification(accessionNumber: String, testType: GerminationTestType) =
      when (testType) {
        GerminationTestType.Lab ->
            "$accessionNumber is scheduled to begin a lab germination test today!"
        GerminationTestType.Nursery ->
            "$accessionNumber is scheduled to begin a nursery germination test today!"
      }

  fun withdrawalDateNotification(accessionNumber: String) =
      "$accessionNumber is scheduled for a withdrawal today!"

  /**
   * The name to use for the project, site, and facility that's automatically created when a new
   * organization is created.
   */
  fun seedBankDefaultName() = "Seed Bank"

  fun dateAndTime(instant: Instant?): String =
      if (instant != null) {
        DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atZone(ZoneOffset.UTC))
      } else {
        "unknown"
      }

  /** Title and body to use for "user added to organization" app notification */
  fun userAddedToOrganizationNotification(orgName: String): NotificationMessage =
      NotificationMessage(
          title = "You've been added to a new organization!",
          body = "You are now a member of $orgName. Welcome!")

  /** Title and body to use for "user added to project" app notification */
  fun userAddedToProjectNotification(projectName: String): NotificationMessage =
      NotificationMessage(
          title = "You've been added to a new project!",
          body = "You are now a member of project $projectName.")

  fun accessionDryingStartNotification(accessionNumber: String): NotificationMessage =
      NotificationMessage(
          title = "Accession scheduled for drying",
          body = dryingMoveDateNotification(accessionNumber))

  fun accessionDryingEndNotification(accessionNumber: String): NotificationMessage =
      NotificationMessage(
          title = "Accession drying ends", body = "$accessionNumber drying end date is today!")

  fun accessionGerminationTestNotification(
      accessionNumber: String,
      testType: GerminationTestType
  ): NotificationMessage =
      NotificationMessage(
          title = "Accession scheduled for germination test",
          body =
              when (testType) {
                GerminationTestType.Lab ->
                    "$accessionNumber is scheduled for a lab germination test today!"
                GerminationTestType.Nursery ->
                    "$accessionNumber is scheduled for a nursery germination test today!"
              })

  fun accessionWithdrawalNotification(accessionNumber: String): NotificationMessage =
      NotificationMessage(
          title = "Accession withdrawal", body = withdrawalDateNotification(accessionNumber))
}
