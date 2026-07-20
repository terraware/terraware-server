package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId

data class OrganizationLocationUpdatedEvent(
    val botanicalCountryCode: String?,
    val countryCode: String?,
    val organizationId: OrganizationId,
)
