package com.terraformation.backend.email.model

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.tables.pojos.DevicesRow

/**
 * Common attributes for classes that can be passed as models when rendering email templates. This
 * includes all the values that are used by the generic header and footer sections but aren't
 * related to the main content of the email.
 */
abstract class EmailTemplateModel(config: TerrawareServerConfig) {
  val webAppUrl: String = "${config.webAppUrl}".trimEnd('/')

  /**
   * Subdirectory of `src/main/resources/templates/email` containing the Freemarker templates to
   * render.
   */
  abstract val templateDir: String
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

class UserAddedToOrganization(
    config: TerrawareServerConfig,
    val admin: IndividualUser,
    val organization: OrganizationModel,
    val organizationHomeUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "user/addedToOrganization"
}

class UserAddedToProject(
    config: TerrawareServerConfig,
    val admin: IndividualUser,
    val project: ProjectModel,
    val organization: OrganizationModel,
    val organizationProjectUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "user/addedToProject"
}

class AccessionMoveToDry(
    config: TerrawareServerConfig,
    val accessionNumber: String,
    val facilityName: String,
    val accessionUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "accession/moveToDry"
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

class AccessionGerminationTest(
    config: TerrawareServerConfig,
    val accessionNumber: String,
    val testType: String,
    val facilityName: String,
    val accessionUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "accession/germinationTest"
}

class AccessionWithdrawal(
    config: TerrawareServerConfig,
    val accessionNumber: String,
    val facilityName: String,
    val accessionUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "accession/withdrawal"
}

class AccessionsAwaitingProcessing(
    config: TerrawareServerConfig,
    val numAccessions: Number,
    val organizationName: String,
    val accessionsUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "accessions/awaitingProcessing"
}

class AccessionsReadyForTesting(
    config: TerrawareServerConfig,
    val numAccessions: Number,
    val weeks: Int,
    val organizationName: String,
    val accessionsUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "accessions/readyForTesting"
}

class AccessionsFinishedDrying(
    config: TerrawareServerConfig,
    val numAccessions: Number,
    val organizationName: String,
    val accessionsUrl: String,
) : EmailTemplateModel(config) {
  override val templateDir: String
    get() = "accessions/finishedDrying"
}
