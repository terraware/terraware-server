package com.terraformation.backend.customer.event

import com.terraformation.backend.db.default_schema.OrganizationId

/** Published when the last member is removed from an organization. */
data class OrganizationAbandonedEvent(val organizationId: OrganizationId)
