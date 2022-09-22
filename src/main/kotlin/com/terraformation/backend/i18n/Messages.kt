package com.terraformation.backend.i18n

import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.CollectionSource
import com.terraformation.backend.db.GrowthForm
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.SeedStorageBehavior
import com.terraformation.backend.db.WithdrawalPurpose
import com.terraformation.backend.db.tables.pojos.DevicesRow
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.isV2Compatible
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import java.time.LocalDate
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
  fun csvBadHeader() = "Incorrect column headings"

  fun csvRequiredFieldMissing() = "Missing required field"

  fun csvDateMalformed() = "Date must be in YYYY-MM-DD format"

  fun csvWrongFieldCount(expected: Int, actual: Int) =
      if (actual == 1) "Row has 1 field; expected $expected"
      else "Row has $actual fields; expected $expected"

  fun csvScientificNameMissing() = "Missing scientific name"

  fun csvScientificNameInvalidChar(invalidChar: String) =
      "Scientific name has invalid character \"$invalidChar\""

  fun csvScientificNameTooShort() = "Scientific name must be at least 2 words"

  fun csvScientificNameTooLong() = "Scientific name must be no more than 4 words"

  fun accessionCsvCollectionSourceInvalid() =
      "Collection source must be one of: $validCollectionSources"

  fun accessionCsvNumberDuplicate(lineNumber: Int) =
      "Accession number already used on line $lineNumber"

  fun accessionCsvNumberExists() = "Accession number already exists"

  fun accessionCsvNumberOfPlantsInvalid() = "Number of plants must be 1 or more"

  fun accessionCsvQuantityInvalid() = "Quantity must be a number greater than 0"

  fun accessionCsvQuantityUnitsInvalid() = "Status must be one of: $validQuantityUnits"

  fun accessionCsvStatusInvalid() = "Status must be one of: $validAccessionStates"

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

  fun historyAccessionCreated() = "created accession"

  fun historyAccessionQuantityUpdated(newQuantity: SeedQuantityModel) =
      "updated the quantity to ${newQuantity.quantity} ${newQuantity.units.displayName.lowercase()}"

  fun historyAccessionStateChanged(newState: AccessionState) =
      "updated the status to ${newState.displayName}"

  fun historyAccessionWithdrawal(
      quantity: SeedQuantityModel?,
      purpose: WithdrawalPurpose?
  ): String {
    val quantityText = quantity?.let { seedQuantity(it) }
    val purposeText = purpose?.displayName?.lowercase()

    return when {
      quantityText != null && purposeText != null -> "withdrew $quantityText for $purposeText"
      quantityText != null -> "withdrew $quantityText"
      purposeText != null -> "withdrew seeds for $purposeText"
      else -> "withdrew seeds"
    }
  }

  private fun seedQuantity(quantity: SeedQuantityModel): String {
    val unitsWord =
        if (quantity.quantity.equalsIgnoreScale(BigDecimal.ONE)) {
          // Do an exhaustive "when" rather than just stripping off the trailing "s" in case we
          // add another unit whose plural name isn't just its singular name with a single "s".
          when (quantity.units) {
            SeedQuantityUnits.Seeds -> "seed"
            SeedQuantityUnits.Grams -> "gram"
            SeedQuantityUnits.Milligrams -> "milligram"
            SeedQuantityUnits.Kilograms -> "kilogram"
            SeedQuantityUnits.Ounces -> "ounce"
            SeedQuantityUnits.Pounds -> "pound"
          }
        } else {
          quantity.units.displayName.lowercase()
        }

    return quantity.quantity.toPlainString() + " $unitsWord"
  }

  private val validAccessionStates =
      AccessionState.values().filter { it.isV2Compatible }.joinToString { it.displayName }
  private val validCollectionSources = CollectionSource.values().joinToString { it.displayName }
  private val validGrowthForms = GrowthForm.values().joinToString { it.displayName }
  private val validQuantityUnits = SeedQuantityUnits.values().joinToString { it.displayName }
  private val validSeedStorageBehaviors =
      SeedStorageBehavior.values().joinToString { it.displayName }

  /** Renders a date in the appropriate format. For now, this is always ISO YYYY-MM-DD format. */
  private fun LocalDate.render() = DateTimeFormatter.ISO_LOCAL_DATE.format(this)
}
