package com.terraformation.backend.customer.event

import com.terraformation.backend.db.OrganizationId

/**
 * Published when an organization's owner deletes it. This indicates the organization has been
 * deleted from the user's point of view; the database may still contain data about the
 * organization.
 */
data class OrganizationDeletedEvent(val organizationId: OrganizationId)
