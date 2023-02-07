package com.terraformation.backend.i18n

import com.terraformation.backend.db.EnumFromReferenceTable
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.isV2Compatible
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import java.text.NumberFormat
import javax.inject.Named
import org.springframework.context.support.ResourceBundleMessageSource

/** Helper class to encapsulate notification message semantics */
data class NotificationMessage(val title: String, val body: String)

/**
 * Renders human-readable messages. All server-generated text that gets displayed to end users
 * should live here rather than inline in the rest of the application. This will make it easier to
 * localize the messages into languages other than English in future versions.
 */
@Named
class Messages {
  private val messageSource =
      ResourceBundleMessageSource().apply {
        setBasename("i18n.Messages")
        setDefaultEncoding("UTF-8")
      }

  private fun getMessage(code: String, vararg args: Any): String {
    return messageSource.getMessage(code, args, currentLocale())
  }

  fun csvBadHeader() = getMessage("csvBadHeader")

  fun csvRequiredFieldMissing() = getMessage("csvRequiredFieldMissing")

  fun csvDateMalformed() = getMessage("csvDateMalformed")

  fun csvWrongFieldCount(expected: Int, actual: Int) =
      getMessage("csvWrongFieldCount", expected, actual)

  fun csvScientificNameMissing() = getMessage("csvScientificNameMissing")

  fun csvScientificNameInvalidChar(invalidChar: String) =
      getMessage("csvScientificNameInvalidChar", invalidChar)

  fun csvScientificNameTooShort() = getMessage("csvScientificNameTooShort")

  fun csvScientificNameTooLong() = getMessage("csvScientificNameTooLong")

  fun csvBooleanValues(value: Boolean): Set<String> {
    val numericValue = if (value) "1" else "0"
    return (0..9)
        .mapNotNull { getMessage("csvBooleanValues.$value.$it").ifBlank { null } }
        .toSet() + numericValue
  }

  fun accessionCsvColumnName(position: Int) = getMessage("accessionCsvColumnName.$position")

  fun accessionCsvCollectionSourceInvalid() =
      getMessage("accessionCsvCollectionSourceInvalid", validCollectionSources)

  fun accessionCsvCountryInvalid() = getMessage("accessionCsvCountryInvalid")

  fun accessionCsvNumberDuplicate(lineNumber: Int) =
      getMessage("accessionCsvNumberDuplicate", lineNumber)

  fun accessionCsvNumberExists() = getMessage("accessionCsvNumberExists")

  fun accessionCsvNumberOfPlantsInvalid() = getMessage("accessionCsvNumberOfPlantsInvalid")

  fun accessionCsvQuantityInvalid() = getMessage("accessionCsvQuantityInvalid")

  fun accessionCsvQuantityUnitsInvalid() =
      getMessage("accessionCsvQuantityUnitsInvalid", validQuantityUnits)

  fun accessionCsvStatusInvalid() = getMessage("accessionCsvStatusInvalid", validAccessionStates)

  fun batchCsvColumnName(position: Int) = getMessage("batchCsvColumnName.$position")

  fun batchCsvQuantityInvalid() = getMessage("batchCsvQuantityInvalid")

  fun speciesCsvColumnName(position: Int) = getMessage("speciesCsvColumnName.$position")

  fun speciesCsvScientificNameExists() = getMessage("speciesCsvScientificNameExists")

  fun speciesCsvFamilyMultipleWords() = getMessage("speciesCsvFamilyMultipleWords")

  fun speciesCsvFamilyInvalidChar(invalidChar: String) =
      getMessage("speciesCsvFamilyInvalidChar", invalidChar)

  fun speciesCsvEndangeredInvalid() = getMessage("speciesCsvEndangeredInvalid")

  fun speciesCsvRareInvalid() = getMessage("speciesCsvRareInvalid")

  fun speciesCsvGrowthFormInvalid() = getMessage("speciesCsvGrowthFormInvalid", validGrowthForms)

  fun speciesCsvSeedStorageBehaviorInvalid() =
      getMessage("speciesCsvSeedStorageBehaviorInvalid", validSeedStorageBehaviors)

  fun speciesCsvEcosystemTypesInvalid() =
      getMessage("speciesCsvEcosystemTypesInvalid", validEcosystemTypes)

  fun searchFieldDisplayName(tableName: String, fieldName: String) =
      getMessage("search.$tableName.$fieldName")

  /** Title and body to use for "user added to organization" app notification */
  fun userAddedToOrganizationNotification(orgName: String): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.user.addedToOrganization.app.title"),
          body = getMessage("notification.user.addedToOrganization.app.body", orgName))

  fun accessionDryingEndNotification(accessionNumber: String): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.accession.dryingEnd.app.title"),
          body = getMessage("notification.accession.dryingEnd.app.body", accessionNumber))

  fun nurserySeedlingBatchReadyNotification(
      batchNumber: String,
      facilityName: String
  ): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.batch.ready.app.title", batchNumber),
          body = getMessage("notification.batch.ready.app.body", batchNumber, facilityName))

  fun facilityIdle(): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.seedBank.idle.app.title"),
          body = getMessage("notification.seedBank.idle.app.body"))

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
                    getMessage("notification.seedBank.lowPower.app.title", facilityName)
                else -> getMessage("notification.seedBank.sensorBounds.app.title", device.name!!)
              },
          body =
              when {
                device.deviceType == "BMU" && timeseriesName == "relative_state_of_charge" ->
                    getMessage("notification.seedBank.lowPower.app.body", value)
                device.deviceType == "sensor" && timeseriesName == "humidity" ->
                    getMessage(
                        "notification.seedBank.sensorBounds.humidity.app.body",
                        device.name!!,
                        value)
                device.deviceType == "sensor" && timeseriesName == "temperature" ->
                    getMessage(
                        "notification.seedBank.sensorBounds.temperature.app.body",
                        device.name!!,
                        value)
                else ->
                    getMessage(
                        "notification.seedBank.sensorBounds.generic.app.body",
                        timeseriesName,
                        device.name!!,
                        value)
              })

  fun unknownAutomationTriggered(
      automationName: String,
      facilityName: String,
      message: String?
  ): NotificationMessage =
      NotificationMessage(
          title =
              getMessage(
                  "notification.seedBank.unknownAutomationTriggered.app.title",
                  automationName,
                  facilityName),
          body = message ?: getMessage("notification.seedBank.unknownAutomationTriggered.app.body"))

  fun deviceUnresponsive(deviceName: String): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.seedBank.deviceUnresponsive.app.title", deviceName),
          body = getMessage("notification.seedBank.deviceUnresponsive.app.body", deviceName))

  fun historyAccessionCreated() = getMessage("historyAccessionCreated")

  fun historyAccessionQuantityUpdated(newQuantity: SeedQuantityModel) =
      getMessage("historyAccessionQuantityUpdated", seedQuantity(newQuantity))

  fun historyAccessionStateChanged(newState: AccessionState) =
      getMessage("historyAccessionStateChanged", newState.getDisplayName(currentLocale()))

  fun historyAccessionWithdrawal(
      quantity: SeedQuantityModel?,
      purpose: WithdrawalPurpose?
  ): String {
    val quantityText = quantity?.let { seedQuantity(it) }

    // Use static message IDs so IDE can detect missing/misspelled ones
    return when {
      quantityText != null && purpose != null ->
          getMessage("historyAccessionQuantityWithdrawnFor$purpose", quantityText)
      quantityText != null -> getMessage("historyAccessionQuantityWithdrawn", quantityText)
      purpose != null -> getMessage("historyAccessionWithdrownFor$purpose")
      else -> getMessage("historyAccessionWithdrawn")
    }
  }

  fun timeZoneWithCity(timeZoneName: String, cityName: String) =
      getMessage("timeZoneWithCity", timeZoneName, cityName)

  fun freezerName(number: Int) = getMessage("seedBankFreezerName", number)

  fun refrigeratorName(number: Int) = getMessage("seedBankRefrigeratorName", number)

  private fun listDelimiter() = getMessage("listDelimiter")

  private fun seedQuantity(quantity: SeedQuantityModel): String {
    val formattedNumber =
        NumberFormat.getInstance(currentLocale())
            .apply { maximumFractionDigits = 5 }
            .format(quantity.quantity)

    // This will need to be revisited if/when we support languages with different pluralization
    // rules than English.
    val singularOrPlural =
        if (quantity.quantity.equalsIgnoreScale(BigDecimal.ONE)) "Singular" else "Plural"

    val messageName = "seedQuantity" + quantity.units.name + singularOrPlural

    return getMessage(messageName, formattedNumber)
  }

  private fun <T : EnumFromReferenceTable<*>> getEnumValuesList(values: Array<T>): String {
    val locale = currentLocale()
    return values.joinToString(listDelimiter()) { it.getDisplayName(locale) }
  }

  private val validAccessionStates
    get() = getEnumValuesList(AccessionState.values().filter { it.isV2Compatible }.toTypedArray())
  private val validEcosystemTypes
    get() = getEnumValuesList(EcosystemType.values())
  private val validCollectionSources
    get() = getEnumValuesList(CollectionSource.values())
  private val validGrowthForms
    get() = getEnumValuesList(GrowthForm.values())
  private val validQuantityUnits
    get() = getEnumValuesList(SeedQuantityUnits.values())
  private val validSeedStorageBehaviors
    get() = getEnumValuesList(SeedStorageBehavior.values())
}
