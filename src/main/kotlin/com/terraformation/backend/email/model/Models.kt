package com.terraformation.backend.email.model

import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.TerrawareUser

/**
 * Marker interface to denote classes that can be passed as models when rendering email templates.
 * This is to provide a small amount of compile-time sanity checking and ensure that other random
 * objects don't get passed.
 */
interface EmailTemplateModel

data class FacilityAlertRequested(
    val body: String,
    val facility: FacilityModel,
    val requestedBy: TerrawareUser,
    val subject: String,
) : EmailTemplateModel

data class FacilityIdle(
    val facility: FacilityModel,
    val lastTimeseriesTime: String,
) : EmailTemplateModel

data class UserAddedToOrganization(
    val admin: IndividualUser,
    val organization: OrganizationModel,
    val organizationHomeUrl: String,
    val webAppUrl: String,
) : EmailTemplateModel
