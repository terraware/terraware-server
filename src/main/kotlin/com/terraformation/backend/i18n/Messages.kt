package com.terraformation.backend.i18n

import com.terraformation.backend.db.GrowthForm
import com.terraformation.backend.db.SeedStorageBehavior
import com.terraformation.backend.db.tables.pojos.DevicesRow
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
  fun speciesCsvBadHeader() = "Incorrect column headings"

  fun speciesCsvWrongFieldCount(expected: Int, actual: Int) =
      if (actual == 1) "Row has 1 field; expected $expected"
      else "Row has $actual fields; expected $expected"

  fun speciesCsvScientificNameMissing() = "Missing scientific name"

  fun speciesCsvScientificNameInvalidChar(invalidChar: String) =
      "Scientific name has invalid character \"$invalidChar\""

  fun speciesCsvScientificNameTooShort() = "Scientific name must be at least 2 words"

  fun speciesCsvScientificNameTooLong() = "Scientific name must be no more than 4 words"

  fun speciesCsvScientificNameExists() = "Scientific name already exists"

  fun speciesCsvFamilyMultipleWords() = "Family must be a single word"

  fun speciesCsvFamilyInvalidChar(invalidChar: String) =
      "Family has invalid character \"$invalidChar\""

  fun speciesCsvEndangeredInvalid() = "Endangered value must be \"yes\" or \"no\""

  fun speciesCsvRareInvalid() = "Rare value must be \"yes\" or \"no\""

  fun speciesCsvGrowthFormInvalid() = "Growth form must be one of: $validGrowthForms"

  fun speciesCsvSeedStorageBehaviorInvalid() =
      "Seed storage behavior must be one of: $validSeedStorageBehaviors"

  /** Title and body to use for "user added to organization" app notification */
  fun userAddedToOrganizationNotification(orgName: String): NotificationMessage =
      NotificationMessage(
          title = "You've been added to a new organization!",
          body = "You are now a member of $orgName. Welcome!")

  fun accessionDryingEndNotification(accessionNumber: String): NotificationMessage =
      NotificationMessage(
          title = "An accession has dried",
          body = "$accessionNumber has reached its scheduled drying date.")

  fun facilityIdle(): NotificationMessage =
      NotificationMessage(
          title = "Device manager cannot be detected.",
          body = "Device manager is disconnected. Please check on it.")

  fun sensorBoundsAlert(
      device: DevicesRow,
      facilityName: String,
      timeseriesName: String,
      value: Any
  ): NotificationMessage =
      NotificationMessage(
          title =
              when {
                device.deviceType == "BMU" && timeseriesName == "relative_state_of_charge" ->
                    "Low power warning for $facilityName"
                else -> "${device.name} is out of range."
              },
          body =
              when {
                device.deviceType == "BMU" && timeseriesName == "relative_state_of_charge" ->
                    "The relative state of charge of the solar power system is at $value%."
                device.deviceType == "sensor" && timeseriesName == "humidity" ->
                    "${device.name} has been or is at $value% RH for the past 5 minutes, which " +
                        "is out of threshold. Please check on it."
                device.deviceType == "sensor" && timeseriesName == "temperature" ->
                    "${device.name} has been or is at $value°C for the past 5 minutes, which is " +
                        "out of threshold. Please check on it."
                else -> "$timeseriesName on ${device.name} is $value, which is out of threshold."
              })

  fun unknownAutomationTriggered(
      automationName: String,
      facilityName: String,
      message: String?
  ): NotificationMessage =
      NotificationMessage(
          title = "$automationName triggered at $facilityName",
          body = message ?: "Please check on it.")

  fun deviceUnresponsive(deviceName: String): NotificationMessage =
      NotificationMessage(
          title = "$deviceName cannot be detected.",
          body = "$deviceName cannot be detected. Please check on it.")

  private val validGrowthForms = GrowthForm.values().joinToString { it.displayName }
  private val validSeedStorageBehaviors =
      SeedStorageBehavior.values().joinToString { it.displayName }
}
