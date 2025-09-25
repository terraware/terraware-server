package com.terraformation.backend.email.model

import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.i18n.FormattingResourceBundleModel
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.tracking.model.PlotT0DensityChangedEventModel
import com.terraformation.backend.tracking.model.ReplacementDuration
import com.terraformation.backend.tracking.model.ZoneT0DensityChangedEventModel
import freemarker.core.HTMLOutputFormat
import freemarker.ext.beans.ResourceBundleModel
import freemarker.template.Configuration
import freemarker.template.DefaultObjectWrapperBuilder
import freemarker.template.TemplateModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.ResourceBundle

/**
 * Common attributes for classes that can be passed as models when rendering email templates. This
 * includes all the values that are used by the generic header and footer sections but aren't
 * related to the main content of the email.
 */
abstract class EmailTemplateModel(config: TerrawareServerConfig) {
  val webAppUrl: String = "${config.webAppUrl}".trimEnd('/')
  val manageSettingsUrl: String = "$webAppUrl/myaccount"

  private val bundlesByLocale = mutableMapOf<Locale, ResourceBundle>()
  private val stringsByLocale = mutableMapOf<Locale, ResourceBundleModel>()

  /**
   * Localized strings for the current locale.
   *
   * This isn't directly accessible from template files; use [strings] instead.
   */
  private val bundle: ResourceBundle
    get() {
      return bundlesByLocale.computeIfAbsent(currentLocale()) { locale ->
        ResourceBundle.getBundle("i18n.Messages", locale)
      }
    }

  /**
   * Localized strings for the current locale, wrapped in Freemarker template models that handle
   * escaping. This is callable as `${strings("stringKey")}` in template files.
   */
  val strings: ResourceBundleModel
    get() {
      return stringsByLocale.computeIfAbsent(currentLocale()) { _ ->
        FormattingResourceBundleModel(
            bundle,
            DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_31).build(),
        )
      }
    }

  /**
   * Subdirectory of `src/main/resources/templates/email` containing the Freemarker templates to
   * render.
   */
  abstract val templateDir: String

  /** Renders a localizable string that contains embedded links. */
  fun link(key: String, vararg urls: String): TemplateModel {
    val htmlWithLeftBrackets =
        bundle
            .getString(key)
            .let { HTMLOutputFormat.INSTANCE.escapePlainText(it) }
            .replace("]", "</a>")

    val htmlWithLinks =
        urls.toList().fold(htmlWithLeftBrackets) { html, url ->
          html.replaceFirst("[", """<a href="$url" class="text-link">""")
        }

    return htmlWithLinks.let { HTMLOutputFormat.INSTANCE.fromMarkup(it) }
  }

  fun dateString(date: LocalDate): String =
      DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(currentLocale()).format(date)
}

class DocumentsUpdate(
    config: TerrawareServerConfig,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "documentsUpdate"
}

class FacilityAlertRequested(
    config: TerrawareServerConfig,
    val body: String,
    val facility: FacilityModel,
    val requestedBy: TerrawareUser,
    val subject: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "facility/alert"
}

class FacilityIdle(
    config: TerrawareServerConfig,
    val facility: FacilityModel,
    val facilityMonitoringUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "facility/idle"
}

class SensorBoundsAlert(
    config: TerrawareServerConfig,
    val automation: AutomationModel,
    val device: DevicesRow,
    val facility: FacilityModel,
    val value: Any,
    val facilityMonitoringUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() {
      return when {
        device.deviceType == "BMU" && automation.timeseriesName == "relative_state_of_charge" ->
            "device/lowPower"
        device.deviceType == "sensor" && automation.timeseriesName == "humidity" ->
            "device/humidity"
        device.deviceType == "sensor" && automation.timeseriesName == "temperature" ->
            "device/temperature"
        else -> "device/sensorBounds"
      }
    }
}

class UnknownAutomationTriggered(
    config: TerrawareServerConfig,
    val automation: AutomationModel,
    val facility: FacilityModel,
    val message: String?,
    val facilityMonitoringUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "device/unknownAutomation"
}

class DeviceUnresponsive(
    config: TerrawareServerConfig,
    val device: DevicesRow,
    val facility: FacilityModel,
    val facilityMonitoringUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "device/unresponsive"
}

class UserAddedToOrganization(
    config: TerrawareServerConfig,
    val admin: IndividualUser,
    val organization: OrganizationModel,
    val organizationHomeUrl: String,
    val user: IndividualUser,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "user/addedToOrganization"
}

class UserAddedToTerraware(
    config: TerrawareServerConfig,
    val admin: IndividualUser,
    val organization: OrganizationModel,
    val terrawareRegistrationUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "user/addedToTerraware"
}

class AccessionDryingEnd(
    config: TerrawareServerConfig,
    val accessionNumber: String,
    val facilityName: String,
    val accessionUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "accession/dryingEnd"
}

class NurserySeedlingBatchReady(
    config: TerrawareServerConfig,
    val seedlingBatchNumber: String,
    val seedlingBatchUrl: String,
    val nurseryName: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "nursery/seedlingBatchReady"
}

class SeedFundReportCreated(
    config: TerrawareServerConfig,
    val year: String,
    val quarter: String,
    val reportUrl: String,
) : EmailTemplateModel(config) {
  constructor(
      config: TerrawareServerConfig,
      year: Int,
      quarter: Int,
      reportUrl: String,
  ) : this(config, "$year", "$quarter", reportUrl)

  override val templateDir: String
    get() = "seedFundReport/created"
}

class ObservationStarted(
    config: TerrawareServerConfig,
    val observationsUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "observation/started"
}

class ObservationUpcoming(
    config: TerrawareServerConfig,
    val plantingSiteName: String,
    val startDate: LocalDate,
    val observationsUrl: String,
    val appStoreUrl: String,
    val googlePlayUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "observation/upcoming"

  val startDateString: String
    get() = dateString(startDate)
}

class ObservationScheduled(
    config: TerrawareServerConfig,
    val organizationName: String,
    val plantingSiteName: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "observation/scheduled"

  val startDateString: String
    get() = dateString(startDate)

  val endDateString: String
    get() = dateString(endDate)
}

class ObservationRescheduled(
    config: TerrawareServerConfig,
    val organizationName: String,
    val plantingSiteName: String,
    val originalStartDate: LocalDate,
    val originalEndDate: LocalDate,
    val newStartDate: LocalDate,
    val newEndDate: LocalDate,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "observation/rescheduled"

  val originalStartDateString: String
    get() = dateString(originalStartDate)

  val originalEndDateString: String
    get() = dateString(originalEndDate)

  val newStartDateString: String
    get() = dateString(newStartDate)

  val newEndDateString: String
    get() = dateString(newEndDate)
}

class ScheduleObservation(
    config: TerrawareServerConfig,
    val organizationId: OrganizationId,
    val plantingSiteId: PlantingSiteId,
    val plantingSiteName: String,
    val observationsUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "observation/schedule"
}

class ScheduleObservationReminder(
    config: TerrawareServerConfig,
    val organizationId: OrganizationId,
    val plantingSiteId: PlantingSiteId,
    val plantingSiteName: String,
    val observationsUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "observation/scheduleReminder"
}

class ObservationNotScheduled(
    config: TerrawareServerConfig,
    val organizationName: String,
    val plantingSiteName: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "observation/notScheduled"
}

class ObservationNotStarted(
    config: TerrawareServerConfig,
    val plantingSiteName: String,
    val contactUsUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "observation/notStarted"
}

class ObservationPlotReplaced(
    config: TerrawareServerConfig,
    val organizationName: String,
    val plantingSiteName: String,
    val justification: String,
    val duration: ReplacementDuration,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "observation/plotReplaced"
}

class MissingContact(
    config: TerrawareServerConfig,
    val organizationId: OrganizationId,
    val organizationName: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "organization/missingContact"
}

class PlantingSeasonRescheduled(
    config: TerrawareServerConfig,
    val organizationName: String,
    val plantingSiteName: String,
    val oldStartDate: LocalDate,
    val oldEndDate: LocalDate,
    val newStartDate: LocalDate,
    val newEndDate: LocalDate,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "plantingSeason/rescheduled"
}

class PlantingSeasonScheduled(
    config: TerrawareServerConfig,
    val organizationName: String,
    val plantingSiteName: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "plantingSeason/scheduled"
}

class PlantingSeasonStarted(
    config: TerrawareServerConfig,
    val plantingSiteName: String,
    val inventoryUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "plantingSeason/started"
}

class PlantingSeasonNotScheduled(
    config: TerrawareServerConfig,
    val plantingSiteName: String,
    val plantingSiteUrl: String,
    val notificationNumber: Int,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "plantingSeason/notScheduled"
}

class PlantingSeasonNotScheduledSupport(
    config: TerrawareServerConfig,
    val organizationName: String,
    val plantingSiteName: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "plantingSeason/notScheduledSupport"
}

class ParticipantProjectAdded(
    config: TerrawareServerConfig,
    val adminName: String,
    val organizationName: String,
    val participantName: String,
    val projectName: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "participant/projectAdded"
}

class ParticipantProjectSpeciesAdded(
    config: TerrawareServerConfig,
    val deliverableUrl: String,
    val participantName: String,
    val projectName: String,
    val speciesName: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "participantProjectSpecies/added"
}

class ParticipantProjectSpeciesEdited(
    config: TerrawareServerConfig,
    val deliverableUrl: String,
    val participantName: String,
    val speciesName: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "participantProjectSpecies/edited"
}

class ParticipantProjectRemoved(
    config: TerrawareServerConfig,
    val adminName: String,
    val organizationName: String,
    val participantName: String,
    val projectName: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "participant/projectRemoved"
}

class ApplicationSubmitted(
    config: TerrawareServerConfig,
    val applicationUrl: String,
    val organizationName: String,
    val submissionDate: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "application/submitted"
}

class DeliverableReadyForReview(
    config: TerrawareServerConfig,
    val deliverableUrl: String,
    val deliverable: DeliverableSubmissionModel,
    val participantName: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "deliverable/readyForReview"
}

class DeliverableStatusUpdated(
    config: TerrawareServerConfig,
    val deliverableUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "deliverable/statusUpdated"
}

class SupportRequestSubmitted(
    config: TerrawareServerConfig,
    val type: String,
    val key: String,
    val summary: String,
    val description: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "support/requestSubmitted"
}

class PlantingSiteMapEdited(
    config: TerrawareServerConfig,
    val addedToOrRemovedFrom: String,
    val areaHaDifference: String,
    val organizationName: String,
    val plantingSiteName: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "plantingSite/mapEdited"
}

class CompletedSectionVariableUpdated(
    config: TerrawareServerConfig,
    val documentName: String,
    val documentUrl: String,
    val projectName: String,
    val sectionName: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "document/completedSectionVariableUpdated"
}

class FunderAddedToFundingEntity(
    config: TerrawareServerConfig,
    val fundingEntityName: String,
    val funderPortalRegistrationUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "funder/addedToFundingEntity"
}

class AcceleratorReportUpcoming(
    config: TerrawareServerConfig,
    val reportPrefix: String,
    val acceleratorReportUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "acceleratorReport/upcoming"
}

class AcceleratorReportSubmitted(
    config: TerrawareServerConfig,
    val projectDealName: String,
    val reportPrefix: String,
    val acceleratorReportConsoleUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "acceleratorReport/submitted"
}

class AcceleratorReportPublished(
    config: TerrawareServerConfig,
    val projectDealName: String,
    val reportPrefix: String,
    val funderReportUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "acceleratorReport/published"
}

class T0DataSet(
    config: TerrawareServerConfig,
    val monitoringPlots: List<PlotT0DensityChangedEventModel>,
    val plantingZones: List<ZoneT0DensityChangedEventModel>,
    val organizationName: String,
    val plantingSiteId: PlantingSiteId,
    val plantingSiteName: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "observation/t0Set"

  val manageT0SettingsUrl: String = "$webAppUrl/observations/$plantingSiteId/survival-rate-settings"
}

class GenericEmail(
    config: TerrawareServerConfig,
    val emailBody: String,
    val subject: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "generic"
}
