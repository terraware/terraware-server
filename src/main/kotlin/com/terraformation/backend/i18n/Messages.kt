package com.terraformation.backend.i18n

import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.accelerator.MODULE_EVENT_NOTIFICATION_LEAD_TIME
import com.terraformation.backend.db.LocalizableEnum
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.PlantMaterialSourcingMethod
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SuccessionalGroup
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.isV2Compatible
import com.terraformation.backend.support.atlassian.model.SupportRequestType
import com.terraformation.backend.util.equalsIgnoreScale
import jakarta.inject.Named
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
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
        // Make the handling of single quote characters consistent regardless of whether or not
        // strings contain placeholders.
        setAlwaysUseMessageFormat(true)
        setBasename("i18n.Messages")
        setDefaultEncoding("UTF-8")
      }

  private fun getMessage(code: String, vararg args: Any): String {
    return messageSource.getMessage(code, args, currentLocale())
  }

  fun applicationPreScreenFailureBadSize(
      country: String,
      totalMinimum: Int,
      totalMaximum: Int,
      mangroveMinimum: Int? = null,
  ) =
      if (mangroveMinimum == null) {
        getMessage("applicationPreScreen.failure.badSize", country, totalMinimum, totalMaximum)
      } else {
        getMessage(
            "applicationPreScreen.failure.badSize.mangrove",
            country,
            totalMinimum,
            mangroveMinimum,
            totalMaximum,
        )
      }

  fun applicationPreScreenBoundaryInNoCountry() =
      getMessage("applicationPreScreen.failure.boundaryInNoCountry")

  fun applicationPreScreenFailureIneligibleCountry(country: String) =
      getMessage("applicationPreScreen.failure.ineligibleCountry", country)

  fun applicationPreScreenFailureMismatchCountries(
      boundaryCountry: String,
      projectCountry: String,
  ) = getMessage("applicationPreScreen.failure.mismatchCountries", boundaryCountry, projectCountry)

  fun applicationPreScreenFailureMonocultureTooHigh(maximum: Int) =
      getMessage("applicationPreScreen.failure.monocultureTooHigh", maximum)

  fun applicationPreScreenFailureMultipleCountries() =
      getMessage("applicationPreScreen.failure.multipleCountries")

  fun applicationPreScreenFailureNoBoundary() =
      getMessage("applicationPreScreen.failure.noBoundary")

  fun applicationPreScreenFailureNoCountry() = getMessage("applicationPreScreen.failure.noCountry")

  fun applicationPreScreenFailureTooFewSpecies(minimum: Int) =
      getMessage("applicationPreScreen.failure.tooFewSpecies", minimum)

  fun applicationModulesIncomplete() = getMessage("application.failure.modulesIncomplete")

  fun applicationSubmittedNotification(organizationName: String): NotificationMessage =
      NotificationMessage(
          getMessage("notification.application.submitted.app.title"),
          getMessage("notification.application.submitted.app.body", organizationName),
      )

  fun csvBadHeader() = getMessage("csvBadHeader")

  fun csvRequiredFieldMissing() = getMessage("csvRequiredFieldMissing")

  fun csvDateMalformed() = getMessage("csvDateMalformed")

  fun csvNameLineBreak() = getMessage("csvNameLineBreak")

  fun csvWrongFieldCount(expected: Int, actual: Int) =
      getMessage("csvWrongFieldCount", expected, actual)

  fun csvScientificNameMissing() = getMessage("csvScientificNameMissing")

  fun csvScientificNameInvalidChar(invalidChar: String) =
      getMessage("csvScientificNameInvalidChar", invalidChar)

  fun csvScientificNameTooShort() = getMessage("csvScientificNameTooShort")

  fun csvScientificNameTooLong() = getMessage("csvScientificNameTooLong")

  fun csvSubLocationNotFound() = getMessage("csvSubLocationNotFound")

  fun csvBooleanValues(value: Boolean): Set<String> {
    val numericValue = if (value) "1" else "0"
    return getMessage("csvBooleanValues.$value").split('\n').map { it.trim() }.toSet() +
        numericValue
  }

  fun acceleratorReportUpcoming(reportPrefix: String): NotificationMessage {
    return NotificationMessage(
        title = getMessage("notification.acceleratorReport.upcoming.app.title", reportPrefix),
        body = getMessage("notification.acceleratorReport.upcoming.app.body", reportPrefix),
    )
  }

  fun acceleratorReportSubmitted(
      projectDealName: String,
      reportPrefix: String,
  ): NotificationMessage {
    return NotificationMessage(
        title =
            getMessage(
                "notification.acceleratorReport.submitted.app.title",
                reportPrefix,
                projectDealName,
            ),
        body =
            getMessage(
                "notification.acceleratorReport.submitted.app.body",
                projectDealName,
                reportPrefix,
            ),
    )
  }

  fun acceleratorReportPublished(
      projectDealName: String,
      reportPrefix: String,
  ): NotificationMessage {
    return NotificationMessage(
        title = getMessage("notification.acceleratorReport.published.app.title", projectDealName),
        body =
            getMessage(
                "notification.acceleratorReport.published.app.body",
                projectDealName,
                reportPrefix,
            ),
    )
  }

  fun accessionCsvColumnName(position: Int) = getMessage("accessionCsvColumnName.$position")

  fun accessionCsvCollectionSourceInvalid() =
      getMessage("accessionCsvCollectionSourceInvalid", validCollectionSources)

  fun accessionCsvCountryInvalid() = getMessage("accessionCsvCountryInvalid")

  fun accessionCsvLatitudeInvalid() = getMessage("accessionCsvLatitudeInvalid")

  fun accessionCsvLatitudeLongitude() = getMessage("accessionCsvLatitudeLongitude")

  fun accessionCsvLongitudeInvalid() = getMessage("accessionCsvLongitudeInvalid")

  fun accessionCsvNonZeroUsedUpQuantity() =
      getMessage(
          "accessionCsvNonZeroUsedUpQuantity",
          AccessionState.UsedUp.getDisplayName(currentLocale()),
      )

  fun accessionCsvNumberDuplicate(lineNumber: Int) =
      getMessage("accessionCsvNumberDuplicate", lineNumber)

  fun accessionCsvNumberExists() = getMessage("accessionCsvNumberExists")

  fun accessionCsvNumberOfPlantsInvalid() = getMessage("accessionCsvNumberOfPlantsInvalid")

  fun accessionCsvQuantityInvalid() = getMessage("accessionCsvQuantityInvalid")

  fun accessionCsvQuantityUnitsInvalid() =
      getMessage("accessionCsvQuantityUnitsInvalid", validQuantityUnits)

  fun accessionCsvStatusInvalid() = getMessage("accessionCsvStatusInvalid", validAccessionStates)

  fun activityCreated(
      activityDate: LocalDate,
      activityType: ActivityType,
      projectDealName: String,
  ) =
      NotificationMessage(
          title =
              getMessage("notification.accelerator.activity.created.app.title", projectDealName),
          body =
              getMessage(
                  "notification.accelerator.activity.created.app.body",
                  activityType.getDisplayName(currentLocale()),
                  activityDate,
              ),
      )

  fun batchCsvColumnName(position: Int) = getMessage("batchCsvColumnName.$position")

  fun batchCsvQuantityInvalid() = getMessage("batchCsvQuantityInvalid")

  fun eventSubjectFullText(subjectClass: KClass<*>, vararg args: Any) =
      getMessage("${eventSubjectPrefix(subjectClass)}.full", *args)

  fun eventSubjectShortText(subjectClass: KClass<*>, vararg args: Any) =
      getMessage("${eventSubjectPrefix(subjectClass)}.short", *args)

  fun monitoringPlotNortheastCorner(plotNumber: Long) =
      getMessage("monitoringPlotNortheastCorner", plotNumber)

  fun monitoringPlotNorthwestCorner(plotNumber: Long) =
      getMessage("monitoringPlotNorthwestCorner", plotNumber)

  fun monitoringPlotSoutheastCorner(plotNumber: Long) =
      getMessage("monitoringPlotSoutheastCorner", plotNumber)

  fun monitoringPlotSouthwestCorner(
      plotNumber: Long,
  ) =
      getMessage(
          "monitoringPlotSouthwestCorner",
          plotNumber,
      )

  fun monitoringPlotDescription(
      plotType: String,
      plantingZoneName: String,
      plantingSubzoneName: String,
  ) = getMessage("monitoringPlotDescription", plotType, plantingZoneName, plantingSubzoneName)

  fun monitoringPlotTypePermanent() = getMessage("monitoringPlotTypePermanent")

  fun monitoringPlotTypeTemporary() = getMessage("monitoringPlotTypeTemporary")

  fun speciesCsvColumnName(position: Int) = getMessage("speciesCsvColumnName.$position")

  fun speciesCsvScientificNameExists() = getMessage("speciesCsvScientificNameExists")

  fun speciesCsvFamilyMultipleWords() = getMessage("speciesCsvFamilyMultipleWords")

  fun speciesCsvFamilyInvalidChar(invalidChar: String) =
      getMessage("speciesCsvFamilyInvalidChar", invalidChar)

  fun speciesCsvConservationCategoryInvalid() =
      getMessage("speciesCsvConservationCategoryInvalid", validConservationCategories)

  fun speciesCsvRareInvalid() = getMessage("speciesCsvRareInvalid")

  fun speciesCsvGrowthFormInvalid() = getMessage("speciesCsvGrowthFormInvalid", validGrowthForms)

  fun speciesCsvSeedStorageBehaviorInvalid() =
      getMessage("speciesCsvSeedStorageBehaviorInvalid", validSeedStorageBehaviors)

  fun speciesCsvEcosystemTypesInvalid() =
      getMessage("speciesCsvEcosystemTypesInvalid", validEcosystemTypes)

  fun speciesCsvSuccessionalGroupInvalid() =
      getMessage("speciesCsvSuccessionalGroupInvalid", validSuccessionalGroups)

  fun speciesCsvPlantMaterialSourcingMethodInvalid() =
      getMessage("speciesCsvPlantMaterialSourcingMethodInvalid", validPlantMaterialSourcingMethods)

  fun searchFieldDisplayName(tableName: String, fieldName: String) =
      getMessage("search.$tableName.$fieldName")

  fun userAddedToOrganizationNotification(orgName: String): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.user.addedToOrganization.app.title"),
          body = getMessage("notification.user.addedToOrganization.app.body", orgName),
      )

  fun accessionDryingEndNotification(accessionNumber: String): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.accession.dryingEnd.app.title"),
          body = getMessage("notification.accession.dryingEnd.app.body", accessionNumber),
      )

  fun moduleEventStartingNotification(
      eventType: EventType,
      moduleName: String,
  ): NotificationMessage =
      NotificationMessage(
          title =
              getMessage(
                  "notification.module.eventStarting.title",
                  eventType.getDisplayName(currentLocale()),
                  MODULE_EVENT_NOTIFICATION_LEAD_TIME.toMinutes().toString(),
              ),
          body =
              getMessage(
                  "notification.module.eventStarting.body",
                  eventType.getDisplayName(currentLocale()),
                  moduleName,
              ),
      )

  fun moduleRecordedSessionNotification(moduleName: String): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.module.recordedSession.title"),
          body = getMessage("notification.module.recordedSession.body", moduleName),
      )

  fun nurserySeedlingBatchReadyNotification(
      batchNumber: String,
      facilityName: String,
  ): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.batch.ready.app.title", batchNumber),
          body = getMessage("notification.batch.ready.app.body", batchNumber, facilityName),
      )

  fun facilityIdle(): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.seedBank.idle.app.title"),
          body = getMessage("notification.seedBank.idle.app.body"),
      )

  fun observationScheduleReminder(): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.observation.scheduleReminder.app.title"),
          body = getMessage("notification.observation.scheduleReminder.app.body"),
      )

  fun observationSchedule(): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.observation.schedule.app.title"),
          body = getMessage("notification.observation.schedule.app.body"),
      )

  fun observationStarted(): NotificationMessage {
    return NotificationMessage(
        title = getMessage("notification.observation.started.app.title"),
        body = getMessage("notification.observation.started.app.body"),
    )
  }

  fun observationUpcoming(plantingSiteName: String, startDate: LocalDate): NotificationMessage {
    val startDateString =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(currentLocale())
            .format(startDate)
    return NotificationMessage(
        title = getMessage("notification.observation.upcoming.app.title"),
        body =
            getMessage(
                "notification.observation.upcoming.app.body",
                plantingSiteName,
                startDateString,
            ),
    )
  }

  fun plantingSeasonNotScheduled(notificationNumber: Int): NotificationMessage {
    return NotificationMessage(
        title =
            getMessage("notification.plantingSeason.notScheduled.$notificationNumber.app.title"),
        body = getMessage("notification.plantingSeason.notScheduled.$notificationNumber.app.body"),
    )
  }

  fun plantingSeasonStarted(plantingSiteName: String): NotificationMessage {
    return NotificationMessage(
        title = getMessage("notification.plantingSeason.started.app.title"),
        body = getMessage("notification.plantingSeason.started.app.body", plantingSiteName),
    )
  }

  fun deliverableReadyForReview(participantName: String): NotificationMessage {
    return NotificationMessage(
        title = "Review a submitted deliverable",
        body = "A deliverable from $participantName is ready for review for approval.",
    )
  }

  fun deliverableStatusUpdated(): NotificationMessage {
    return NotificationMessage(
        title = getMessage("notification.deliverable.statusUpdated.app.title"),
        body = getMessage("notification.deliverable.statusUpdated.app.body"),
    )
  }

  fun participantProjectSpeciesAddedToProject(
      participantName: String,
      projectName: String,
      speciesName: String,
  ): NotificationMessage {
    return NotificationMessage(
        title =
            getMessage("notification.participantProjectSpecies.added.app.title", participantName),
        body =
            getMessage(
                "notification.participantProjectSpecies.added.app.body",
                speciesName,
                projectName,
            ),
    )
  }

  fun participantProjectSpeciesApprovedSpeciesEdited(
      participantName: String,
      speciesName: String,
  ): NotificationMessage {
    return NotificationMessage(
        title =
            getMessage("notification.participantProjectSpecies.edited.app.title", participantName),
        body = getMessage("notification.participantProjectSpecies.edited.app.body", speciesName),
    )
  }

  fun sensorBoundsAlert(
      device: DevicesRow,
      facilityName: String,
      timeseriesName: String,
      value: Any,
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
                        value,
                    )
                device.deviceType == "sensor" && timeseriesName == "temperature" ->
                    getMessage(
                        "notification.seedBank.sensorBounds.temperature.app.body",
                        device.name!!,
                        value,
                    )
                else ->
                    getMessage(
                        "notification.seedBank.sensorBounds.generic.app.body",
                        timeseriesName,
                        device.name!!,
                        value,
                    )
              },
      )

  fun unknownAutomationTriggered(
      automationName: String,
      facilityName: String,
      message: String?,
  ): NotificationMessage =
      NotificationMessage(
          title =
              getMessage(
                  "notification.seedBank.unknownAutomationTriggered.app.title",
                  automationName,
                  facilityName,
              ),
          body = message ?: getMessage("notification.seedBank.unknownAutomationTriggered.app.body"),
      )

  fun deviceUnresponsive(deviceName: String): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.seedBank.deviceUnresponsive.app.title", deviceName),
          body = getMessage("notification.seedBank.deviceUnresponsive.app.body", deviceName),
      )

  fun seedFundReportCreated(year: Int, quarter: Int): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.seedFundReport.created.app.title", "$year", "$quarter"),
          body = getMessage("notification.seedFundReport.created.app.body", "$year", "$quarter"),
      )

  fun completedSectionVariableUpdated(
      documentName: String,
      sectionName: String,
  ): NotificationMessage =
      NotificationMessage(
          title = getMessage("notification.document.completedSectionVariableUpdated.app.title"),
          body =
              getMessage(
                  "notification.document.completedSectionVariableUpdated.app.body",
                  documentName,
                  sectionName,
              ),
      )

  fun historyAccessionCreated() = getMessage("historyAccessionCreated")

  fun historyAccessionQuantityUpdated(newQuantity: SeedQuantityModel) =
      getMessage("historyAccessionQuantityUpdated", seedQuantity(newQuantity))

  fun historyAccessionStateChanged(newState: AccessionState) =
      getMessage("historyAccessionStateChanged", newState.getDisplayName(currentLocale()))

  fun historyAccessionWithdrawal(
      quantity: SeedQuantityModel?,
      purpose: WithdrawalPurpose?,
  ): String {
    val quantityText = quantity?.let { seedQuantity(it) }

    // Use static message IDs so IDE can detect missing/misspelled ones
    return when {
      quantityText != null && purpose != null ->
          getMessage("historyAccessionQuantityWithdrawnFor$purpose", quantityText)
      quantityText != null -> getMessage("historyAccessionQuantityWithdrawn", quantityText)
      purpose != null -> getMessage("historyAccessionWithdrawnFor$purpose")
      else -> getMessage("historyAccessionWithdrawn")
    }
  }

  fun formerUser() = getMessage("formerUser")

  fun terraformationTeam() = getMessage("terraformationTeam")

  fun inaccessibleUser() = getMessage("inaccessibleUser")

  fun timeZoneWithCity(timeZoneName: String, cityName: String) =
      getMessage("timeZoneWithCity", timeZoneName, cityName)

  fun freezerName(number: Int) = getMessage("seedBankFreezerName", number)

  fun refrigeratorName(number: Int) = getMessage("seedBankRefrigeratorName", number)

  fun supportRequestTypeName(type: SupportRequestType) =
      when (type) {
        SupportRequestType.BugReport -> getMessage("support.requestType.bugReport")
        SupportRequestType.ContactUs -> getMessage("support.requestType.contactUs")
        SupportRequestType.FeatureRequest -> getMessage("support.requestType.featureRequest")
      }

  /**
   * Returns the string that should be used to separate items in a list. This is hardwired in the
   * code rather than in a message bundle because Phrase seems to not send strings to translators if
   * they only have punctuation and whitespace.
   */
  private fun listDelimiter() =
      when (currentLocale().language) {
        "gx" -> "_ "
        "zh" -> "、"
        else -> ", "
      }

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

  private fun <T : LocalizableEnum<*>> getEnumValuesList(values: List<T>): String {
    val locale = currentLocale()
    return values.joinToString(listDelimiter()) { it.getDisplayName(locale) }
  }

  fun manifestCsvColumnName(position: Int): String = getMessage("manifestCsvColumnName.$position")

  fun manifestCsvDataTypeRequired() = getMessage("manifestCsvDataTypeRequired")

  fun manifestCsvNameRequired() = getMessage("manifestCsvNameRequired")

  fun manifestCsvDataTypeRequiresOptions() = getMessage("manifestCsvDataTypeRequiresOptions")

  fun manifestCsvRecommendationNotUnique() = getMessage("manifestCsvRecommendationNotUnique")

  fun manifestCsvSectionParentMustBeSection() = getMessage("manifestCsvSectionParentMustBeSection")

  fun manifestCsvSelectOptionsNotUnique() = getMessage("manifestCsvSelectOptionsNotUnique")

  fun manifestCsvStableIdNotUnique() = getMessage("manifestCsvStableIdNotUnique")

  fun manifestCsvStableIdRequired() = getMessage("manifestCsvStableIdRequired")

  fun manifestCsvTopLevelNameNotUnique() = getMessage("manifestCsvTopLevelNameNotUnique")

  fun manifestCsvVariableNameNotUniqueWithinParent() =
      getMessage("manifestCsvVariableNameNotUniqueWithinParent")

  fun manifestCsvVariableParentDoesNotExist() = getMessage("manifestCsvVariableParentDoesNotExist")

  fun manifestCsvWrongDataTypeForChild() = getMessage("manifestCsvWrongDataTypeForChild")

  fun variablesCsvColumnName(position: Int): String = getMessage("variablesCsvColumnName.$position")

  fun variablesCsvDataTypeRequired() = getMessage("variablesCsvDataTypeRequired")

  fun variableCsvDeliverableDoesNotExist() = getMessage("variablesCsvDeliverableDoesNotExist")

  fun variablesCsvDependencyConfigIncomplete() =
      getMessage("variablesCsvDependencyConfigIncomplete")

  fun variablesCsvDependencyVariableStableIdDoesNotExist() =
      getMessage("variablesCsvDependencyVariableStableIdDoesNotExist")

  fun variablesCsvDependsOnItself() = getMessage("variablesCsvDependsOnItself")

  fun variablesCsvNameRequired() = getMessage("variablesCsvNameRequired")

  fun variablesCsvDataTypeRequiresOptions() = getMessage("variablesCsvDataTypeRequiresOptions")

  fun variablesCsvRecommendationNotUnique() = getMessage("variablesCsvRecommendationNotUnique")

  fun variablesCsvSectionParentMustBeSection() =
      getMessage("variablesCsvSectionParentMustBeSection")

  fun variablesCsvSelectOptionsNotUnique() = getMessage("variablesCsvSelectOptionsNotUnique")

  fun variablesCsvStableIdNotUnique() = getMessage("variablesCsvStableIdNotUnique")

  fun variablesCsvStableIdRequired() = getMessage("variablesCsvStableIdRequired")

  fun variablesCsvTopLevelNameNotUnique() = getMessage("variablesCsvTopLevelNameNotUnique")

  fun variablesCsvVariableNameNotUniqueWithinParent() =
      getMessage("variablesCsvVariableNameNotUniqueWithinParent")

  fun variablesCsvVariableParentDoesNotExist() =
      getMessage("variablesCsvVariableParentDoesNotExist")

  fun variablesCsvWrongDataTypeForChild() = getMessage("variablesCsvWrongDataTypeForChild")

  private fun eventSubjectPrefix(kClass: KClass<*>) =
      "eventSubject.${kClass.findAnnotation<JsonTypeName>()!!.value}"

  private val validAccessionStates
    get() = getEnumValuesList(AccessionState.entries.filter { it.isV2Compatible })

  private val validEcosystemTypes
    get() = getEnumValuesList(EcosystemType.entries)

  private val validCollectionSources
    get() = getEnumValuesList(CollectionSource.entries)

  private val validConservationCategories
    get() = ConservationCategory.entries.map { it.jsonValue }

  private val validGrowthForms
    get() = getEnumValuesList(GrowthForm.entries)

  private val validPlantMaterialSourcingMethods
    get() = getEnumValuesList(PlantMaterialSourcingMethod.entries)

  private val validQuantityUnits
    get() = getEnumValuesList(SeedQuantityUnits.entries)

  private val validSeedStorageBehaviors
    get() = getEnumValuesList(SeedStorageBehavior.entries)

  private val validSuccessionalGroups
    get() = getEnumValuesList(SuccessionalGroup.entries)
}
