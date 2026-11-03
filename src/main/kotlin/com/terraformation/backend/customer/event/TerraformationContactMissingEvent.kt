package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId

data class TerraformationContactMissingEvent(
    val organizationId: OrganizationId,
    val organizationName: String,
    val subject: String,
    val body: String,
)
