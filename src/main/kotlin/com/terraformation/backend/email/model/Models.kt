package com.terraformation.backend.email.model

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.TerrawareUser

/**
 * Common attributes for classes that can be passed as models when rendering email templates. This
 * includes all the values that are used by the generic header and footer sections but aren't
 * related to the main content of the email.
 */
open class EmailTemplateModel(config: TerrawareServerConfig) {
  val webAppUrl: String = "${config.webAppUrl}".trimEnd('/')
}

class FacilityAlertRequested(
    config: TerrawareServerConfig,
    val body: String,
    val facility: FacilityModel,
    val requestedBy: TerrawareUser,
    val subject: String,
) : EmailTemplateModel(config)

class FacilityIdle(
    config: TerrawareServerConfig,
    val facility: FacilityModel,
    val lastTimeseriesTime: String,
) : EmailTemplateModel(config)

class UserAddedToOrganization(
    config: TerrawareServerConfig,
    val admin: IndividualUser,
    val organization: OrganizationModel,
    val organizationHomeUrl: String,
) : EmailTemplateModel(config)

class UserAddedToProject(
    config: TerrawareServerConfig,
    val admin: IndividualUser,
    val project: ProjectModel,
    val organization: OrganizationModel,
    val organizationProjectUrl: String,
) : EmailTemplateModel(config)

class AccessionDryingStart(
    config: TerrawareServerConfig,
    val accessionNumber: String,
    val organization: OrganizationModel,
    val accessionUrl: String,
) : EmailTemplateModel(config)

class AccessionDryingEnd(
    config: TerrawareServerConfig,
    val accessionNumber: String,
    val organization: OrganizationModel,
    val accessionUrl: String,
) : EmailTemplateModel(config)

class AccessionGerminationTest(
    config: TerrawareServerConfig,
    val accessionNumber: String,
    val testType: String,
    val organization: OrganizationModel,
    val accessionUrl: String,
) : EmailTemplateModel(config)

class AccessionWithdrawal(
    config: TerrawareServerConfig,
    val accessionNumber: String,
    val organization: OrganizationModel,
    val accessionUrl: String,
) : EmailTemplateModel(config)
